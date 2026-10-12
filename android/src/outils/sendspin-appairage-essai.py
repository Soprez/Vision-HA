"""Essai de l'appairage par PIN du prototype (WebView de Vision sur l'émulateur) contre aiosendspin 9.0.0 en local."""
# Lancement : python sendspin-appairage-essai.py <dossier> (venv avec aiosendspin[server]==9.0.0 et harnais.py = tests/helpers/sendspin-server.py de sendspin-js, Apache-2.0).
import asyncio, json, os, re, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, os.path.dirname(__file__))
import harnais
from aiohttp.test_utils import TestServer

PORT = 8930
_orig = TestServer.__init__
def _init(self, app, *, host="127.0.0.1", port=None, **kw):
    _orig(self, app, host="127.0.0.1", port=PORT, **kw)
harnais.AiohttpTestServer.__init__ = _init

ADB = [os.environ.get("ADB", "adb"), "-s", "emulator-5572"]
def adb(*a): return subprocess.run(ADB + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
def lancer():
    adb("shell", "am", "force-stop", "org.soprez.vision")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", "org.soprez.vision/.PrototypeSendspinActivity", "--es", "hote", "10.0.2.2", "--es", "port", str(PORT))
def pin_affiche(timeout=20):
    fin = time.time() + timeout
    while time.time() < fin:
        for l in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines():
            m = re.search(r'"ev":"pin","t":\d+,"pin":"(\d+)"', l)
            if m: return m.group(1)
        time.sleep(0.5)
    return None
def journal(motif):
    return [l[19:] for l in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines() if re.search(motif, l)]

async def main():
    srv = harnais.HarnessServer(Path(sys.argv[1]))
    print("serveur", await srv.start())
    # 1. PIN faux : refusé
    lancer()
    c = await srv.wait_client(20000); print("client", c)
    print("debut", await srv.begin_pin_pairing("dynamic_pin"))
    print("attente", await srv.wait_for_pin_request(20000))
    p = pin_affiche(); print("PIN affiché :", p)
    faux = ("1" if p[0] != "1" else "2") * len(p)
    t = time.time(); r = await srv.submit_pin(faux); print("PIN faux ->", r.get("status"), r.get("reason"), r.get("has_pairing_record"), f"{(time.time()-t)*1000:.0f} ms")
    # 2. Bon PIN
    lancer()
    c = await srv.wait_client(20000)
    print("debut", await srv.begin_pin_pairing("dynamic_pin"))
    print("attente", await srv.wait_for_pin_request(20000))
    p = pin_affiche(); print("PIN affiché :", p)
    t = time.time(); r = await srv.submit_pin(p); print("bon PIN ->", r.get("status"), r.get("reason"), "dossier:", r.get("has_pairing_record"), f"{(time.time()-t)*1000:.0f} ms")
    await asyncio.sleep(3)
    print("\n".join(journal(r'appairage|pin|erreur')[-8:]))
    # 3. Reconnexion : appairé (PSK long terme), lecture possible sans nouvel appairage
    lancer()
    c = await srv.wait_client(20000)
    await asyncio.sleep(4)
    print("reconnexion", json.dumps(await srv.status(c if isinstance(c, str) else None))[:600])
    print("\n".join(journal(r'"connecte":true')[-1:])[:300])
    await srv.shutdown()
asyncio.run(main())
