"""Lot 6 : l'appairage par PIN du client natif (émulateur) contre aiosendspin 9.0.0 en local (PIN lu à l'écran via le journal)."""
# Lancement : python sendspin-natif-appairage-essai.py <dossier> (venv avec aiosendspin[server]==9.0.0 et harnais.py = tests/helpers/sendspin-server.py de sendspin-js, Apache-2.0).
import asyncio, json, os, re, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, os.path.dirname(__file__))
import harnais
from aiohttp.test_utils import TestServer
PORT = 8930
_orig = TestServer.__init__
def _init(self, app, *, host="127.0.0.1", port=None, **kw): _orig(self, app, host="127.0.0.1", port=PORT, **kw)
harnais.AiohttpTestServer.__init__ = _init
ADB = [os.environ.get("ADB", "adb"), "-s", "emulator-5572"]
def adb(*a): return subprocess.run(ADB + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
def lancer():
    adb("shell", "am", "force-stop", "org.soprez.vision"); adb("logcat", "-c")
    adb("shell", "am", "start", "-n", "org.soprez.vision/.EssaiSendspinNatifActivity", "--es", "hote", "10.0.2.2", "--es", "port", str(PORT))
def pin_affiche(t=20):
    fin = time.time() + t
    while time.time() < fin:
        for l in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines()[::-1]:
            m = re.search(r'"pin":"(\d+)"', l)
            if m: return m.group(1)
        time.sleep(0.5)
async def main():
    srv = harnais.HarnessServer(Path(sys.argv[1])); await srv.start()
    lancer(); c = await srv.wait_client(30000); print("client", c["client_id"][:8], c["supported_pair_methods"])
    # 1. PIN faux
    print("debut", await srv.begin_pin_pairing("dynamic_pin")); print("attente", await srv.wait_for_pin_request(20000))
    p = pin_affiche(); print("PIN à l'écran :", p)
    r = await srv.submit_pin(("1" if p[0] != "1" else "2") * len(p)); print("PIN faux ->", r["status"], r["reason"], "dossier:", r["has_pairing_record"])
    # 2. Bon PIN
    lancer(); await srv.wait_client(30000)
    print("debut", await srv.begin_pin_pairing("dynamic_pin")); await srv.wait_for_pin_request(20000)
    p = pin_affiche(); print("PIN à l'écran :", p)
    t = time.time(); r = await srv.submit_pin(p); print("bon PIN ->", r["status"], r["reason"], "dossier:", r["has_pairing_record"], f"{(time.time()-t)*1000:.0f} ms")
    await asyncio.sleep(2)
    # 3. Reconnexion : clé long terme
    lancer(); c = await srv.wait_client(30000); await asyncio.sleep(3)
    st = await srv.status(c["client_id"]); print("reconnexion :", st["psk_category"], st["trust_level"], st["active_roles"])
    print("\n".join(l[19:200] for l in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines() if "appairage" in l or "confiance" in l)[-600:])
    await srv.shutdown()
asyncio.run(main())
