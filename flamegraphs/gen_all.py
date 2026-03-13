import subprocess, json, os, re

BASE = r"D:\exeris-systems\exeris-kernel"
OUT  = os.path.join(BASE, "flamegraphs")
os.makedirs(OUT, exist_ok=True)

JOBS = [
    {
        "title":  "Bootstrap — CPU Samples",
        "jfr":    os.path.join(BASE, r"exeris-kernel-core\target\jfr-reports\CoreBootstrapZeroAllocTckTest-Bootstrap-20260304-195634.jfr"),
        "events": "jdk.ExecutionSample,jdk.NativeMethodSample",
        "mode":   "cpu",
        "html":   os.path.join(OUT, "01-bootstrap-cpu.html"),
    },
    {
        "title":  "Telemetry — Heap Allocations",
        "jfr":    os.path.join(BASE, r"exeris-kernel-core\target\jfr-reports\CoreTelemetryZeroAllocTckTest-Telemetry-20260304-195638.jfr"),
        "events": "jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB",
        "mode":   "alloc",
        "html":   os.path.join(OUT, "02-telemetry-alloc.html"),
    },
    {
        "title":  "Enterprise Memory — Heap Allocations",
        "jfr":    os.path.join(BASE, r"exeris-kernel-enterprise\target\jfr-reports\EnterpriseZeroGcJfrMonitorTckTest-Memory-20260304-191845.jfr"),
        "events": "jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB",
        "mode":   "alloc",
        "html":   os.path.join(OUT, "03-enterprise-alloc.html"),
    },
    {
        "title":  "Community Memory — Heap Allocations",
        "jfr":    os.path.join(BASE, r"exeris-kernel-community\target\jfr-reports\CommunityZeroGcJfrMonitorTckTest-Memory-20260304-195647.jfr"),
        "events": "jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB",
        "mode":   "alloc",
        "html":   os.path.join(OUT, "04-community-alloc.html"),
    },
]


def load_jfr(jfr_path, events_csv):
    r = subprocess.run(
        ["jfr", "print", "--events", events_csv, "--json", jfr_path],
        capture_output=True,
    )
    raw = r.stdout
    if not raw:
        return {"recording": {"events": []}}
    for enc in ("utf-16", "utf-8-sig", "utf-8"):
        try:
            return json.loads(raw.decode(enc))
        except Exception:
            pass
    return {"recording": {"events": []}}


def alloc_bytes(values):
    for k in ("allocationSize", "size", "objectSize"):
        v = values.get(k)
        if v is None:
            continue
        if isinstance(v, (int, float)):
            return max(1, int(v))
        m = re.match(r"\d+", str(v).replace(",","").replace("_",""))
        if m:
            return max(1, int(m.group()))
    return 1


def build_tree(data, mode):
    root = {"name": "root", "value": 0, "children": {}}
    for ev in data.get("recording", {}).get("events", []):
        vals   = ev.get("values", {})
        stack  = vals.get("stackTrace")
        if not stack:
            continue
        frames = stack.get("frames", [])
        if not frames:
            continue
        weight = alloc_bytes(vals) if mode == "alloc" else 1
        names = []
        for f in reversed(frames):
            m   = f.get("method", {})
            cls = m.get("type", {}).get("name", "?").replace("/", ".")
            mth = m.get("name", "?")
            names.append(f"{cls}.{mth}")
        node = root
        node["value"] += weight
        for n in names:
            if n not in node["children"]:
                node["children"][n] = {"name": n, "value": 0, "children": {}}
            node = node["children"][n]
            node["value"] += weight
    return root


def to_d3(node):
    r = {"name": node["name"], "value": node["value"]}
    if node["children"]:
        r["children"] = [to_d3(c) for c in node["children"].values()]
    return r


TMPL = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Exeris — {title}</title>
<style>
  *{{box-sizing:border-box;margin:0;padding:0}}
  body{{background:#0f1117;color:#cdd6f4;font-family:'JetBrains Mono',monospace,sans-serif;padding:14px}}
  h1{{font-size:13px;color:#89b4fa;letter-spacing:.06em;margin-bottom:3px}}
  .meta{{font-size:11px;color:#6c7086;margin-bottom:10px}}
  .meta b{{color:#a6e3a1}}
  #bar{{display:flex;gap:8px;margin-bottom:6px;align-items:center}}
  #q{{background:#1e2030;border:1px solid #313244;color:#cdd6f4;padding:4px 8px;border-radius:4px;font-size:11px;width:260px}}
  button{{background:#313244;border:none;color:#cdd6f4;padding:4px 10px;border-radius:4px;cursor:pointer;font-size:11px}}
  button:hover{{background:#45475a}}
  #chart{{width:100%;height:82vh;background:#1e2030;border-radius:6px;border:1px solid #313244;overflow:hidden}}
  .d3-flame-graph rect{{stroke:#0f1117;stroke-width:.4px}}
  #warn{{display:none;background:#313244;color:#f38ba8;padding:8px 14px;border-radius:4px;font-size:12px;margin-top:6px}}
</style>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/d3-flame-graph@4.1.3/dist/d3-flamegraph.css">
</head>
<body>
<h1>&#x1F525; Exeris Kernel &mdash; {title}</h1>
<div class="meta">
  source: <b>{source}</b> &nbsp;|&nbsp;
  mode: <b>{mode_label}</b> &nbsp;|&nbsp;
  total: <b>{total}</b> &nbsp;|&nbsp;
  events: <b>{events}</b>
</div>
<div id="bar">
  <input id="q" type="text" placeholder="Search frame (regex)&hellip;">
  <button onclick="chart.resetZoom()">Reset zoom</button>
  <button onclick="clearSearch()">Clear</button>
</div>
<div id="warn">&#x26A0;&nbsp; No allocation samples found &mdash; hot path is allocation-free &#x2713;</div>
<div id="chart"></div>
<script src="https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/d3-flame-graph@4.1.3/dist/d3-flamegraph.min.js"></script>
<script>
const DATA  = {data_json};
const MODE  = "{mode}";
const PAL   = {colors};
function fmt(v){{
  if(MODE==="alloc"){{
    if(v>=1048576) return (v/1048576).toFixed(2)+" MB";
    if(v>=1024)    return (v/1024).toFixed(1)+" kB";
    return v+" B";
  }}
  return v+" samples";
}}
const chart = flamegraph()
  .width(document.getElementById("chart").clientWidth||1400)
  .height(window.innerHeight*.80)
  .cellHeight(18).minFrameSize(0).transitionDuration(250)
  .tooltip(true).title("").selfValue(false).sort(true)
  .resetHeightOnZoom(true)
  .color(d=>{{
    const n=d.data.name||"";
    if(n.startsWith("eu.exeris"))  return "#a6e3a1";
    if(n.startsWith("jdk."))       return "#fab387";
    if(n.startsWith("java.")  )    return "#89dceb";
    if(n.startsWith("sun.")||n.startsWith("com.sun.")) return "#b4befe";
    if(n.startsWith("org.junit"))  return "#f9e2af";
    return PAL[Math.abs(n.split("").reduce((a,c)=>a+c.charCodeAt(0),0))%PAL.length];
  }})
  .details(d=>d?`${{d.data.name}}  [${{fmt(d.data.value)}}]`:"");
const el=document.getElementById("chart");
if(DATA.value===0){{
  document.getElementById("warn").style.display="block";
  el.style.display="none";
}}else{{
  d3.select(el).call(chart.data(DATA));
}}
function clearSearch(){{chart.clear();document.getElementById("q").value="";}}
document.getElementById("q").addEventListener("input",e=>{{
  const t=e.target.value.trim();
  if(t) chart.search(t); else chart.clear();
}});
window.addEventListener("resize",()=>{{
  chart.width(el.clientWidth);
  d3.select(el).call(chart.data(DATA));
}});
</script>
</body>
</html>
"""


def main():
    done = []
    for job in JOBS:
        print(f"\n[{job['title']}]")
        if not os.path.exists(job["jfr"]):
            print("  SKIP — file not found")
            continue

        data   = load_jfr(job["jfr"], job["events"])
        n_ev   = len(data.get("recording", {}).get("events", []))
        print(f"  events   : {n_ev}")

        root   = build_tree(data, job["mode"])
        total  = root["value"]
        print(f"  weight   : {total}")

        d3data = to_d3(root)

        if job["mode"] == "cpu":
            mode_label = "CPU samples"
            colors     = '["#b22222","#cd853f","#daa520","#8b0000","#a0522d","#d2691e"]'
            total_str  = f"{total:,} samples"
        else:
            mode_label = "heap bytes"
            colors     = '["#1a6b8a","#2e8b57","#3cb371","#20b2aa","#008b8b","#5f9ea0"]'
            if   total >= 1048576: total_str = f"{total/1048576:.2f} MB"
            elif total >= 1024:    total_str = f"{total/1024:.1f} kB"
            else:                  total_str = f"{total:,} B"

        html = TMPL.format(
            title      = job["title"],
            source     = os.path.basename(job["jfr"]),
            mode       = job["mode"],
            mode_label = mode_label,
            total      = total_str,
            events     = job["events"].replace(",", " + "),
            data_json  = json.dumps(d3data, separators=(",", ":")),
            colors     = colors,
        )

        with open(job["html"], "w", encoding="utf-8") as f:
            f.write(html)
        kb = os.path.getsize(job["html"]) // 1024
        print(f"  written  : {job['html']}  ({kb} kB)")
        done.append(job["html"])

    print("\n" + "="*70)
    print("Open in browser:")
    for p in done:
        print(f"  file:///{p.replace(os.sep, '/')}")


if __name__ == "__main__":
    main()

