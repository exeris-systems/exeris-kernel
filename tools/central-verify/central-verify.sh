#!/usr/bin/env bash
#
# Central-verify (supply-chain integrity, consumer side).
#
# Downloads every `eu.exeris` coordinate a release publishes FROM MAVEN CENTRAL and verifies what a
# consumer actually receives:
#
#   1. every detached `.asc` verifies, and was made by the project signing key pinned by
#      fingerprint in this repository (`exeris-release-key.asc`, pinned in `PINNED_FINGERPRINT`);
#   2. every published checksum (.md5 .sha1 .sha256 .sha512) matches the bytes served;
#   3. every coordinate ships a CycloneDX SBOM, it is signed, and it names the coordinate it ships
#      beside (group, artifact, version).
#
# Why a separate tool from `release-readiness`: that gate proves what the build SIGNED before upload.
# This one proves what Central SERVES afterwards — a different set of bytes, reached over a different
# channel, into a keyring that holds only the pinned key. The files are fetched into an empty
# directory and verified in a throwaway GnuPG home; the build's `~/.m2` and the runner's keyring are
# never consulted.
#
# What is verified is derived from the release tag's own POMs (`<modules>` plus the aggregator,
# minus `<excludeArtifact>`), not from Central's directory listing: a listing answers "what happens
# to be there", which cannot detect a coordinate that never arrived.
#
# Usage:
#   tools/central-verify/central-verify.sh [--version X.Y.Z] [--self-test]
#                                          [--wait-minutes N] [--workdir DIR]
#                                          [--base-url URL] [--key-file FILE]
#
#   --version       release to verify; default is the highest local `vMAJOR.MINOR.PATCH` tag
#   --self-test     run the negative controls first: the verifier must PASS an untouched artifact,
#                   then FAIL on a tampered jar, on a tampered SBOM, on a wrong pinned fingerprint
#                   and under a genuinely different key. A verifier that cannot fail proves nothing.
#   --wait-minutes  poll up to N minutes for the release to appear on Central (propagation after
#                   publication is not instant); default 0
#   --workdir       keep downloads here (default: a temporary directory, removed on exit)
#
# Requires: gpg, python3, network access to repo1.maven.org, and the release tag in the local clone
# (`git fetch --tags`).
set -euo pipefail

case "${1:-}" in
  -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$REPO_ROOT/tools/central-verify"
cd "$REPO_ROOT"

command -v gpg >/dev/null || { echo "central-verify: FAILED — gpg not on PATH"; exit 1; }
command -v python3 >/dev/null || { echo "central-verify: FAILED — python3 not on PATH"; exit 1; }

# The project signing key. The fingerprint is the pin; the .asc beside this script is only the
# transport for the public half and is checked against the pin before anything is verified. The same
# fingerprint is the `issuer fpr` on every signature served from Central for 0.12.0 and the key is
# published on keys.openpgp.org.
export PINNED_FINGERPRINT="1CEF65BBECBA77D4080D51C95745CD1C91D5B5B4"
export DEFAULT_KEY_FILE="$HERE/exeris-release-key.asc"
export REPO_ROOT

exec python3 -I - "$@" <<'PY'
import argparse, concurrent.futures, hashlib, json, os, pathlib, re, secrets, shutil, subprocess, sys, tempfile, time
import urllib.error, urllib.request
import xml.etree.ElementTree as ET

NS = '{http://maven.apache.org/POM/4.0.0}'
GROUP = 'eu.exeris'
PIN = os.environ['PINNED_FINGERPRINT']
REPO = pathlib.Path(os.environ['REPO_ROOT'])
CHECKSUMS = [('md5', 'md5'), ('sha1', 'sha1'), ('sha256', 'sha256'), ('sha512', 'sha512')]


class Failure(Exception):
    pass


def git(*args):
    proc = subprocess.run(['git', '-C', str(REPO), *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise Failure(f'git {" ".join(args)} failed: {proc.stderr.strip()}')
    return proc.stdout


def latest_tag():
    tags = [t for t in git('tag', '-l', 'v*').split() if re.fullmatch(r'v\d+\.\d+\.\d+', t)]
    if not tags:
        raise Failure('no vMAJOR.MINOR.PATCH tag in this clone — fetch tags or pass --version')
    return max(tags, key=lambda t: tuple(int(p) for p in t[1:].split('.')))[1:]


def coordinates(version):
    """(artifactId, packaging) for every coordinate the release tag publishes."""
    tag = f'v{version}'
    root = ET.fromstring(git('show', f'{tag}:pom.xml'))
    have = root.findtext(NS + 'version').strip()
    if have != version:
        raise Failure(f'tag {tag} carries pom version {have}, not {version}')
    excluded = {e.text.strip() for e in root.iter(NS + 'excludeArtifact') if e.text}
    out = [(root.findtext(NS + 'artifactId').strip(), 'pom')]
    for m in root.iter(NS + 'module'):
        mod = ET.fromstring(git('show', f'{tag}:{m.text.strip()}/pom.xml'))
        packaging = (mod.findtext(NS + 'packaging') or 'jar').strip()
        out.append((mod.findtext(NS + 'artifactId').strip(), packaging))
    return [(a, p) for a, p in out if a not in excluded]


def expected_files(artifact, version, packaging):
    stem = f'{artifact}-{version}'
    files = [f'{stem}.pom']
    if packaging != 'pom':
        files += [f'{stem}.jar', f'{stem}-sources.jar', f'{stem}-javadoc.jar']
    files.append(f'{stem}-cyclonedx.json')
    return files


def fetch(url, dest, tries=4):
    last = None
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r, open(dest, 'wb') as f:
                shutil.copyfileobj(r, f)
            return
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                break
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
        time.sleep(2 * (attempt + 1))
    raise Failure(f'cannot fetch {url}: {last}')


class Keyring:
    """A throwaway GnuPG home holding exactly one key, which must be the pinned one."""

    def __init__(self, key_file, fingerprint):
        self.fingerprint = fingerprint.upper()
        self.home = tempfile.mkdtemp(prefix='cv-gpg-')
        os.chmod(self.home, 0o700)
        self._gpg('--import', str(key_file), check=False)
        listed = self._gpg('--with-colons', '--fingerprint', '--list-keys').stdout
        fprs = [l.split(':')[9] for l in listed.splitlines() if l.startswith('fpr:')]
        if not fprs:
            raise Failure(f'{key_file} contains no OpenPGP key')
        # The first fpr record is the primary key; later ones are subkeys of it.
        if fprs[0] != self.fingerprint:
            raise Failure(f'key file fingerprint {fprs[0]} is not the pinned '
                          f'{self.fingerprint} — refusing to trust it')

    def _gpg(self, *args, check=True):
        proc = subprocess.run(['gpg', '--homedir', self.home, '--batch', '--no-tty', *args],
                              capture_output=True, text=True)
        if check and proc.returncode != 0:
            raise Failure(f'gpg {" ".join(args)}: {proc.stderr.strip()}')
        return proc

    def verify(self, sig, data):
        """The signature must verify AND be made by the pinned primary key."""
        proc = self._gpg('--status-fd', '1', '--verify', str(sig), str(data), check=False)
        status = proc.stdout.splitlines()
        valid = [l.split() for l in status if l.startswith('[GNUPG:] VALIDSIG')]
        if proc.returncode != 0 or not valid:
            reason = next((l[len('[GNUPG:] '):] for l in status
                           if l.startswith(('[GNUPG:] BADSIG', '[GNUPG:] ERRSIG', '[GNUPG:] NO_PUBKEY',
                                            '[GNUPG:] EXPKEYSIG', '[GNUPG:] REVKEYSIG'))),
                          proc.stderr.strip().splitlines()[-1] if proc.stderr.strip() else 'no VALIDSIG')
            raise Failure(f'{pathlib.Path(data).name}: signature does not verify ({reason})')
        if valid[0][-1].upper() != self.fingerprint:
            raise Failure(f'{pathlib.Path(data).name}: signed by {valid[0][-1]}, '
                          f'not the pinned {self.fingerprint}')

    def close(self):
        subprocess.run(['gpgconf', '--homedir', self.home, '--kill', 'all'], capture_output=True)
        shutil.rmtree(self.home, ignore_errors=True)


def verify_checksums(path):
    for ext, algo in CHECKSUMS:
        sumfile = path.with_name(path.name + '.' + ext)
        if not sumfile.is_file():
            raise Failure(f'{path.name}: no .{ext} checksum was downloaded')
        want = sumfile.read_text().split()[0].strip().lower()
        h = hashlib.new(algo)
        with open(path, 'rb') as f:
            for chunk in iter(lambda: f.read(1 << 20), b''):
                h.update(chunk)
        if h.hexdigest() != want:
            raise Failure(f'{path.name}: .{ext} checksum mismatch (served {want}, computed {h.hexdigest()})')


def verify_sbom_identity(path, artifact, version):
    try:
        bom = json.loads(path.read_text())
    except ValueError as e:
        raise Failure(f'{path.name}: not valid JSON ({e})')
    if bom.get('bomFormat') != 'CycloneDX':
        raise Failure(f'{path.name}: bomFormat is {bom.get("bomFormat")!r}, not CycloneDX')
    comp = (bom.get('metadata') or {}).get('component') or {}
    got = (comp.get('group'), comp.get('name'), comp.get('version'))
    if got != (GROUP, artifact, version):
        raise Failure(f'{path.name}: SBOM names {got[0]}:{got[1]}:{got[2]}, '
                      f'expected {GROUP}:{artifact}:{version}')


def verify_set(keyring, directory, artifact, version, packaging):
    """Verify one coordinate already downloaded into `directory`. Returns (files, signatures)."""
    files = expected_files(artifact, version, packaging)
    for name in files:
        path = directory / name
        if not path.is_file():
            raise Failure(f'{artifact}: {name} is not on Central')
        sig = directory / (name + '.asc')
        if not sig.is_file():
            raise Failure(f'{artifact}: {name} has no detached signature')
        keyring.verify(sig, path)
        verify_checksums(path)
        if name.endswith('-cyclonedx.json'):
            verify_sbom_identity(path, artifact, version)
    return len(files)


def download(base, directory, artifact, version, packaging):
    directory.mkdir(parents=True, exist_ok=True)
    jobs = [(f'{base}/{GROUP.replace(".", "/")}/{artifact}/{version}/{name}{suffix}',
             directory / (name + suffix))
            for name in expected_files(artifact, version, packaging)
            for suffix in ['', '.asc'] + [f'.{e}' for e, _ in CHECKSUMS]]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for fut in [pool.submit(fetch, url, dest) for url, dest in jobs]:
            fut.result()


def expect_failure(label, needle, fn):
    try:
        fn()
    except Failure as e:
        if needle not in str(e):
            raise Failure(f'negative control "{label}" failed for the wrong reason: {e}')
        print(f'    control FAILED AS EXPECTED  {label}\n        -> {e}')
        return
    raise Failure(f'negative control "{label}" PASSED — the verifier cannot tell good from bad')


def self_test(base, work, version, key_file):
    print('central-verify: negative controls')
    artifact, packaging = 'exeris-kernel-spi', 'jar'
    src = work / 'selftest' / 'pristine'
    download(base, src, artifact, version, packaging)
    jar = f'{artifact}-{version}.jar'
    sbom = f'{artifact}-{version}-cyclonedx.json'

    good = Keyring(key_file, PIN)
    try:
        # Baseline: without it a control could "fail" because the fixture is broken.
        n = verify_set(good, src, artifact, version, packaging)
        print(f'    baseline: pristine {artifact} verifies ({n} files, pinned key)')

        # (i) tampered artefact: one flipped byte in the middle of the jar.
        t = work / 'selftest' / 'tampered-jar'
        shutil.copytree(src, t)
        raw = bytearray((t / jar).read_bytes())
        raw[len(raw) // 2] ^= 0x01
        (t / jar).write_bytes(raw)
        expect_failure('tampered jar: signature', 'signature does not verify',
                       lambda: good.verify(t / (jar + '.asc'), t / jar))
        expect_failure('tampered jar: checksum', 'checksum mismatch',
                       lambda: verify_checksums(t / jar))
        expect_failure('tampered jar: whole coordinate', jar,
                       lambda: verify_set(good, t, artifact, version, packaging))

        # (i-b) an SBOM that is validly signed but about another version must still be refused.
        s = work / 'selftest' / 'wrong-sbom'
        shutil.copytree(src, s)
        doc = json.loads((s / sbom).read_text())
        doc['metadata']['component']['version'] = '0.0.0'
        (s / sbom).write_text(json.dumps(doc))
        expect_failure('SBOM naming another version', 'SBOM names',
                       lambda: verify_sbom_identity(s / sbom, artifact, version))
    finally:
        good.close()

    # (ii) wrong key, two ways. A pin that does not match the key file ...
    other_pin = secrets.token_hex(20).upper()
    expect_failure('wrong pinned fingerprint', 'is not the pinned',
                   lambda: Keyring(key_file, other_pin))

    # ... and a real, valid key that simply is not the project's: signatures must not verify.
    eph = tempfile.mkdtemp(prefix='cv-eph-')
    os.chmod(eph, 0o700)
    try:
        def gpg(*a):
            return subprocess.run(['gpg', '--homedir', eph, '--batch', '--no-tty', *a],
                                  capture_output=True, text=True, check=True).stdout
        gpg('--passphrase', '', '--quick-generate-key', 'central-verify negative control <nobody@example.invalid>',
            'rsa2048', 'sign', 'never')
        fpr = next(l.split(':')[9] for l in gpg('--with-colons', '--list-keys').splitlines()
                   if l.startswith('fpr:'))
        eph_key = pathlib.Path(eph) / 'other.asc'
        eph_key.write_text(gpg('--armor', '--export', fpr))
        other = Keyring(eph_key, fpr)
        try:
            expect_failure('a different, valid key', 'signature does not verify',
                           lambda: other.verify(src / (jar + '.asc'), src / jar))
        finally:
            other.close()
    finally:
        subprocess.run(['gpgconf', '--homedir', eph, '--kill', 'all'], capture_output=True)
        shutil.rmtree(eph, ignore_errors=True)
    print('central-verify: negative controls PASSED (every control failed as expected)')


def main():
    ap = argparse.ArgumentParser(prog='central-verify.sh', add_help=False)
    ap.add_argument('--version')
    ap.add_argument('--self-test', action='store_true')
    ap.add_argument('--wait-minutes', type=int, default=0)
    ap.add_argument('--workdir')
    ap.add_argument('--base-url', default='https://repo1.maven.org/maven2')
    ap.add_argument('--key-file', default=os.environ['DEFAULT_KEY_FILE'])
    args = ap.parse_args(sys.argv[1:])

    version = args.version or latest_tag()
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise Failure(f'{version!r} is not a release version (expected MAJOR.MINOR.PATCH)')
    base = args.base_url.rstrip('/')
    work = pathlib.Path(args.workdir) if args.workdir else pathlib.Path(tempfile.mkdtemp(prefix='cv-'))
    work.mkdir(parents=True, exist_ok=True)
    try:
        if args.self_test:
            # Controls need a release to exist; wait for it the same way the real run does.
            deadline = time.time() + args.wait_minutes * 60
            while True:
                try:
                    fetch(f'{base}/{GROUP.replace(".", "/")}/exeris-kernel-spi/{version}/'
                          f'exeris-kernel-spi-{version}.pom', work / 'probe.pom', tries=1)
                    break
                except Failure:
                    if time.time() >= deadline:
                        raise
                    time.sleep(30)
            self_test(base, work, version, args.key_file)

        coords = coordinates(version)
        print(f'central-verify: {GROUP}:* {version} — {len(coords)} coordinate(s) from tag v{version}, '
              f'fetched from {base}')
        deadline = time.time() + args.wait_minutes * 60
        keyring = Keyring(args.key_file, PIN)
        files = 0
        try:
            for artifact, packaging in coords:
                directory = work / 'release' / artifact
                while True:
                    try:
                        download(base, directory, artifact, version, packaging)
                        break
                    except Failure:
                        if time.time() >= deadline:
                            raise
                        time.sleep(30)
                n = verify_set(keyring, directory, artifact, version, packaging)
                files += n
                print(f'    OK  {artifact:<36} {packaging:<4} {n} file(s), signatures + checksums + SBOM')
        finally:
            keyring.close()
        print(f'central-verify: PASSED — {len(coords)} coordinates, {files} files, each signature made '
              f'by {PIN}, SBOM present and self-describing for every coordinate')
    finally:
        if not args.workdir:
            shutil.rmtree(work, ignore_errors=True)


try:
    main()
except Failure as e:
    print(f'\ncentral-verify: FAILED — {e}')
    sys.exit(1)
PY
