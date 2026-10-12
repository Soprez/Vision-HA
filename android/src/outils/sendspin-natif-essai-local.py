"""Lot 1-3 : le client natif (émulateur) contre aiosendspin 9.0.0 en local : poignée, hello, horloge, flux PCM."""
# Lancement : python sendspin-natif-essai-local.py <dossier> (venv avec aiosendspin[server]==9.0.0 et harnais.py = tests/helpers/sendspin-server.py de sendspin-js, Apache-2.0).
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
def dernier():
    l = [x for x in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines() if '"connecte"' in x]
    return l[-1][l[-1].index("{"):] if l else None
async def main():
    srv = harnais.HarnessServer(Path(sys.argv[1])); await srv.start()
    adb("shell", "am", "force-stop", "org.soprez.vision"); adb("logcat", "-c")
    adb("shell", "am", "start", "-n", "org.soprez.vision/.EssaiSendspinNatifActivity", "--es", "hote", "10.0.2.2", "--es", "port", str(PORT), "--es", "codecs", "pcm")
    c = await srv.wait_client(20000); await srv.trust_unpaired(c["client_id"])
    adb("shell", "am", "force-stop", "org.soprez.vision"); await asyncio.sleep(1); adb("logcat", "-c")
    adb("shell", "am", "start", "-n", "org.soprez.vision/.EssaiSendspinNatifActivity", "--es", "hote", "10.0.2.2", "--es", "port", str(PORT), "--es", "codecs", "pcm")
    c = await srv.wait_client(20000); await asyncio.sleep(1); c = await srv.status(); print("client", {k: c.get(k) for k in ("connected", "psk_category", "active_roles", "supported_pair_methods")})
    await asyncio.sleep(3)
    print("etat", (await srv.status()).get("active_roles"))
    await srv.stream_start()
    for _ in range(8): await srv.send_audio(500); await asyncio.sleep(0.45)
    await asyncio.sleep(2); print("en lecture :", dernier())
    srv.set_volume(10); srv.set_mute(True); await asyncio.sleep(1.5)
    print("après volume 10 + mute :", dernier())
    print("journal :", "\n".join(x for x in adb("logcat", "-d", "-s", "VisionSendspin:*").splitlines() if "flux" in x or "volume" in x or "fermée" in x)[-600:])
    await srv.stream_end(); await asyncio.sleep(1); print("fin :", dernier())
    await srv.shutdown()
asyncio.run(main())
