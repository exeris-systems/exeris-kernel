"""
Exeris Kernel — JFR Flamegraph Generator
"""
import subprocess
import json
import os
import re

JAVA_HOME = os.environ.get("JAVA_HOME", "")
JFR_CMD   = os.path.join(JAVA_HOME, "bin", "jfr") if JAVA_HOME else "jfr"
VENDOR    = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_vendor")
BASE      = r"D:\exeris-systems\exeris-kernel"

CORE_DIR  = rf"{BASE}\exeris-kernel-core\target\jfr-reports"
ENT_DIR   = rf"{BASE}\exeris-kernel-enterprise\target\jfr-reports"
COM_DIR   = rf"{BASE}\exeris-kernel-community\target\jfr-reports"


def _vendor(name: str) -> str:
    with open(os.path.join(VENDOR, name), encoding="utf-8") as f:
        return f.read()


def get_latest_jfr(directory: str, prefix: str) -> str:
    matches = [
        os.path.join(directory, f)
        for f in os.listdir(directory)
        if f.startswith(prefix) and f.endswith(".jfr")
    ]
    if not matches:
        raise FileNotFoundError(f"Brak JFR '{prefix}*' w {directory}")
    return max(matches, key=os.path.getmtime)


RECORDINGS = [
    {
        "id":      "bootstrap",
        "title":   "Bootstrap — CPU Flamegraph",
        "subtitle":"CoreBootstrapZeroAllocTckTest · jdk.ExecutionSample + jdk.NativeMethodSample",
        "jfr_dir": CORE_DIR,
        "jfr_pfx": "CoreBootstrapZeroAllocTckTest-Bootstrap-",
        "events":  ["jdk.ExecutionSample", "jdk.NativeMethodSample"],
        "mode":    "cpu",
        "color":   "#0f1117",
    },
    {
        "id":      "telemetry",
        "title":   "Telemetry — Allocation Flamegraph",
        "subtitle":"CoreTelemetryZeroAllocTckTest · 100k KernelLifecycle · jdk.ObjectAllocationSample + jdk.ObjectAllocationInNewTLAB",
        "jfr_dir": CORE_DIR,
        "jfr_pfx": "CoreTelemetryZeroAllocTckTest-Telemetry-",
        "events":  ["jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB"],
        "mode":    "alloc",
        "color":   "#0d1117",
    },
    {
        "id":      "enterprise",
        "title":   "Enterprise Memory — Allocation Flamegraph (1M sequential)",
        "subtitle":"EnterpriseMemoryFlamegraphHarnessTest · 1M iterations · stackdepth=256 · zero eu.exeris.* allocs",
        "jfr_dir": ENT_DIR,
        "jfr_pfx": "EnterpriseMemoryFlamegraphHarnessTest-Memory-",
        "events":  ["jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB",
                    "jdk.ObjectAllocationOutsideTLAB", "jdk.ExecutionSample", "jdk.NativeMethodSample"],
        "mode":    "alloc",
        "color":   "#0a0f18",
    },
    {
        "id":      "enterprise-vt",
        "title":   "Enterprise Memory — VT Avalanche Flamegraph (1M Virtual Threads)",
        "subtitle":"EnterpriseMemoryFlamegraphHarnessTest · 1M VTs via StructuredTaskScope · stackdepth=256",
        "jfr_dir": ENT_DIR,
        "jfr_pfx": "EnterpriseMemoryFlamegraphHarnessTest-VT-",
        "events":  ["jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB",
                    "jdk.ObjectAllocationOutsideTLAB", "jdk.ExecutionSample", "jdk.NativeMethodSample"],
        "mode":    "alloc",
        "color":   "#0d0a18",
    },
    {
        "id":      "community",
        "title":   "Community Memory — Allocation Flamegraph (1M sequential)",
        "subtitle":"CommunityMemoryFlamegraphHarnessTest · 1M iterations · stackdepth=256 · bounded eu.exeris.* allocs",
        "jfr_dir": COM_DIR,
        "jfr_pfx": "CommunityMemoryFlamegraphHarnessTest-Memory-",
        "events":  ["jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB",
                    "jdk.ObjectAllocationOutsideTLAB", "jdk.ExecutionSample"],
        "mode":    "alloc",
        "color":   "#0c0c1a",
    },
    {
        "id":      "community-vt",
        "title":   "Community Memory — VT Avalanche (1M Virtual Threads)",
        "subtitle":"communityAvalancheMillion · Loom Tax + Object Tax · stackdepth=256 · Enterprise contrast",
        "jfr_dir": COM_DIR,
        "jfr_pfx": "CommunityMemoryFlamegraphHarnessTest-VT-",
        "events":  ["jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB",
                    "jdk.ObjectAllocationOutsideTLAB", "jdk.ExecutionSample", "jdk.NativeMethodSample"],
        "mode":    "alloc",
        "color":   "#1a0a0a",
    },
]


def read_jfr_json(jfr_path: str, events: list[str]) -> dict:
    cmd    = [JFR_CMD, "print", "--events", ",".join(events), "--json", jfr_path]
    result = subprocess.run(cmd, capture_output=True)
    raw    = result.stdout
    if not raw:
        return {"recording": {"events": []}}
    enc = "utf-16" if raw[:2] == b'\xff\xfe' else "utf-8"
    return json.loads(raw.decode(enc, errors="replace"))


def alloc_bytes(values: dict) -> int:
    for k in ("allocationSize", "size", "objectSize"):
        v = values.get(k)
        if v is None:
            continue
        if isinstance(v, (int, float)):
            return max(1, int(v))
        m = re.match(r"\d+", str(v).replace(",", "").replace("_", ""))
        if m:
            return max(1, int(m.group()))
    return 1


def build_tree(data: dict, mode: str) -> dict:
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
        names  = []
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


def to_d3(node: dict) -> dict:
    r = {"name": node["name"], "value": node["value"]}
    if node["children"]:
        r["children"] = [to_d3(c) for c in node["children"].values()]
    return r


def count_frames(node: dict, seen: set) -> None:
    seen.add(node["name"])
    for c in node.get("children", {}).values():
        count_frames(c, seen)


HTML = """\
<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<title>{title}</title>
<style>
  {flamegraph_css}
  * {{ box-sizing: border-box; }}
  body {{
    margin:0; padding:10px 14px 0;
    background:{bg}; color:#cdd6f4;
    font-family:'Segoe UI',sans-serif; font-size:12px;
  }}
  h2   {{ color:#89b4fa; font-size:14px; margin:0 0 2px; font-weight:600; }}
  .sub {{ color:#6c7086; font-size:11px; margin-bottom:6px; }}
  /* ── top info bar ─────────────────────────────────────────────── */
  .infobar {{ display:flex; gap:10px; margin-bottom:6px; flex-wrap:wrap; align-items:center; }}
  .badge  {{
    background:#1e2030; border:1px solid #313244; border-radius:4px;
    padding:2px 9px; color:#a6adc8; font-size:11px; white-space:nowrap;
  }}
  .badge b {{ color:#cdd6f4; }}
  /* ── controls ──────────────────────────────────────────────────── */
  .controls {{ display:flex; gap:8px; margin-bottom:6px; align-items:center; }}
  input[type=text] {{
    background:#1e2030; border:1px solid #313244; color:#cdd6f4;
    padding:3px 8px; border-radius:4px; font-size:11px; width:240px; outline:none;
  }}
  input[type=text]:focus {{ border-color:#89b4fa; }}
  button {{
    background:#313244; border:1px solid #45475a; color:#cdd6f4;
    padding:3px 10px; border-radius:4px; cursor:pointer; font-size:11px;
  }}
  button:hover {{ background:#45475a; }}
  /* ── legend ────────────────────────────────────────────────────── */
  .legend {{
    display:flex; gap:12px; margin-bottom:6px; flex-wrap:wrap;
    padding:5px 8px; background:#1e2030; border:1px solid #313244; border-radius:4px;
  }}
  .legend-item {{ display:flex; align-items:center; gap:5px; font-size:10px; color:#a6adc8; }}
  .dot {{ width:10px; height:10px; border-radius:2px; flex-shrink:0; }}
  /* ── explanation box ───────────────────────────────────────────── */
  .explain {{
    margin-bottom:6px; padding:5px 10px;
    background:#1e2030; border-left:3px solid #89b4fa;
    border-radius:0 4px 4px 0; font-size:11px; color:#a6adc8; line-height:1.6;
  }}
  .explain b {{ color:#cdd6f4; }}
  /* ── chart ─────────────────────────────────────────────────────── */
  #chart {{ width:100%; }}
  .d3-flame-graph rect {{ stroke:{bg}; stroke-width:.3px; }}
  .d3-flame-graph text {{ font-size:10px !important; }}
  .d3-flame-graph-tip {{
    background:#181825 !important; border:1px solid #45475a !important;
    color:#cdd6f4 !important; font-size:11px !important;
    max-width:700px !important; word-break:break-all !important;
    padding:6px 10px !important; line-height:1.5 !important;
  }}
</style>
</head>
<body>
  <h2>&#x1F525; {title}</h2>
  <div class="sub">{subtitle}</div>

  <div class="infobar">
    <div class="badge">total weight: <b>{total}</b></div>
    <div class="badge">unique frames: <b>{frames}</b></div>
    <div class="badge">mode: <b>{mode_label}</b></div>
    <div class="badge">stackdepth: <b>256</b></div>
    <div class="badge">truncated: <b>0</b></div>
    <div class="badge">JDK: <b>OpenJDK 26 + ZGC</b></div>
  </div>

  <div class="legend">
    <div class="legend-item"><div class="dot" style="background:#a6e3a1"></div>eu.exeris.* (kernel)</div>
    <div class="legend-item"><div class="dot" style="background:#89dceb"></div>java.* (JDK stdlib)</div>
    <div class="legend-item"><div class="dot" style="background:#fab387"></div>jdk.internal.* (JDK internals)</div>
    <div class="legend-item"><div class="dot" style="background:#b4befe"></div>sun.* / com.sun.*</div>
    <div class="legend-item"><div class="dot" style="background:#f9e2af"></div>org.junit.* (test harness)</div>
    <div class="legend-item"><div class="dot" style="background:#cba6f7"></div>java.util.concurrent.* (VT/STS)</div>
    <div class="legend-item"><div class="dot" style="background:#74c7ec"></div>inne</div>
  </div>

  <div class="explain">
    {explain_text}
  </div>

  <div class="controls">
    <input id="q" type="text" placeholder="Search frame (regex)… np: eu\\.exeris|allocate|CAS">
    <button id="btnReset">&#x21BA; Reset zoom</button>
    <button id="btnClear">&#x2715; Clear search</button>
  </div>

  <div id="chart"></div>

<script>{d3_js}</script>
<script>{flamegraph_js}</script>
<script>
  var DATA = {data_json};

  function colorFor(name) {{
    if (!name) return '#74c7ec';
    if (name.startsWith('eu.exeris.'))          return '#a6e3a1';
    if (name.startsWith('java.util.concurrent') ||
        name.startsWith('jdk.internal.vm'))     return '#cba6f7';
    if (name.startsWith('java.'))               return '#89dceb';
    if (name.startsWith('jdk.internal.'))       return '#fab387';
    if (name.startsWith('jdk.'))                return '#fab387';
    if (name.startsWith('sun.') ||
        name.startsWith('com.sun.'))            return '#b4befe';
    if (name.startsWith('org.junit.'))          return '#f9e2af';
    return '#74c7ec';
  }}

  function render() {{
    var W = window.innerWidth  - 30;
    var H = window.innerHeight - 210;
    if (H < 200) H = 200;
    document.getElementById('chart').innerHTML = '';
    var chart = flamegraph()
      .width(W).height(H)
      .cellHeight(20).minFrameSize(1)
      .transitionDuration(200)
      .tooltip(true).title('').selfValue(false).sort(true)
      .resetHeightOnZoom(true)
      .color(function(d) {{ return colorFor(d.data.name); }})
      .label(function(d) {{
        var n = d.data.name || '';
        var short = n.split('.').pop();
        return short;
      }});
    d3.select('#chart').datum(DATA).call(chart);
    document.getElementById('btnReset').onclick = function() {{ chart.resetZoom(); }};
    document.getElementById('btnClear').onclick = function() {{
      chart.clear(); document.getElementById('q').value = '';
    }};
    document.getElementById('q').oninput = function() {{
      var v = this.value.trim();
      if (v) chart.search(v); else chart.clear();
    }};
  }}

  if (document.readyState === 'complete') {{ render(); }}
  else {{ window.addEventListener('load', render); }}
  window.addEventListener('resize', render);
</script>
</body>
</html>
"""


def main():
    out_dir = os.path.dirname(os.path.abspath(__file__))

    for rec in RECORDINGS:
        print(f"\n[{rec['id']}]")
        try:
            jfr_path = get_latest_jfr(rec["jfr_dir"], rec["jfr_pfx"])
        except FileNotFoundError as e:
            print(f"  [ERROR] {e}")
            continue
        print(f"  JFR: {os.path.basename(jfr_path)}")

        data         = read_jfr_json(jfr_path, rec["events"])
        events_count = len(data.get("recording", {}).get("events", []))
        print(f"  events: {events_count}")

        root    = build_tree(data, rec["mode"])
        total   = root["value"]
        d3_data = to_d3(root)
        seen    = set()
        count_frames(root, seen)

        if rec["mode"] == "cpu":
            mode_label  = "CPU samples"
            total_str   = f"{total:,} samples"
            explain_text = (
                "<b>CPU Flamegraph</b> — każdy frame to metoda na stosie w momencie próbkowania CPU. "
                "Szerokość = % czasu CPU. Kliknij frame żeby zoom. "
                "<b>eu.exeris.*</b> (zielony) = kod kernela. "
                "Im szersza podstawa tym więcej czasu CPU zajmuje ta ścieżka."
            )
        else:
            mode_label  = "heap bytes"
            explain_text = (
                "<b>Allocation Flamegraph</b> — każdy frame to metoda na stosie w momencie alokacji. "
                "Szerokość = łączna liczba bajtów zaallokowanych przez tę ścieżkę. "
                "<b>eu.exeris.*</b> (zielony) = obiekty jądra. "
                "<b>jdk.internal.*</b> (pomarańczowy) = pamięć JDK (np. ConfinedSession, MemorySegment). "
                "Stackdepth=256, 0 truncated traces. "
                f"Próba: {total_str if 'total_str' in dir() else '?'}."
            )
            if   total >= 1_048_576: total_str = f"{total / 1_048_576:.2f} MB"
            elif total >= 1_024:     total_str = f"{total / 1_024:.1f} kB"
            else:                    total_str = f"{total:,} B"
            explain_text = (
                "<b>Allocation Flamegraph</b> — każdy frame to metoda na stosie w momencie alokacji. "
                "Szerokość = łączna liczba bajtów zaallokowanych przez tę ścieżkę. "
                "<b>eu.exeris.*</b> (zielony) = obiekty jądra. "
                "<b>jdk.internal.*</b> (pomarańczowy) = pamięć JDK (np. ConfinedSession, MemorySegment). "
                f"Stackdepth=256, 0 truncated. Łączny weight: <b>{total_str}</b>."
            )

        html = HTML.format(
            title         = rec["title"],
            subtitle      = rec["subtitle"],
            bg            = rec["color"],
            total         = total_str,
            frames        = f"{len(seen):,}",
            mode_label    = mode_label,
            explain_text  = explain_text,
            data_json     = json.dumps(d3_data, separators=(",", ":")),
            flamegraph_css= _vendor("d3-flamegraph.css"),
            d3_js         = _vendor("d3.min.js"),
            flamegraph_js = _vendor("d3-flamegraph.min.js"),
        )

        out_path = os.path.join(out_dir, f"{rec['id']}-flamegraph.html")
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(html)

        kb = os.path.getsize(out_path) // 1024
        print(f"  ✓ {out_path}  ({kb} KB, {total_str}, {len(seen):,} frames)")

    print("\n✅ Gotowe:")
    for rec in RECORDINGS:
        p = os.path.join(out_dir, f"{rec['id']}-flamegraph.html").replace("\\", "/")
        print(f"   file:///{p}")


if __name__ == "__main__":
    main()





