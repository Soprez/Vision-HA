"""Banc du lecteur Vision (SlimProto) : le vrai serveur aioslimproto de Music Assistant pilote Vision.

Vision (émulateur) se relie à 10.0.2.2:3483 ; on joue un WAV servi en HTTP, puis pause, reprise, volume, titre
suivant à la suite (gapless), fin. Chaque étape doit voir la bonne réponse de Vision. Écrit « BANC OK » ou « BANC KO ».
"""
import asyncio
import functools
import http.server
import socketserver
import sys
import threading
import time

from aioslimproto.server import SlimServer
from aioslimproto.models import PlayerState

DOSSIER = sys.argv[1]
PORT_HTTP = 8099


def http_serveur():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=DOSSIER)
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", PORT_HTTP), h) as s:
        s.serve_forever()


async def attendre(cond, quoi, delai=25):
    fin = time.time() + delai
    while time.time() < fin:
        if cond():
            print("  ok :", quoi, flush=True)
            return True
        await asyncio.sleep(0.2)
    print("  ÉCHEC :", quoi, flush=True)
    return False


async def main():
    threading.Thread(target=http_serveur, daemon=True).start()
    serveur = SlimServer(cli_port=None, cli_port_json=None)
    await serveur.start()
    print("serveur prêt, en attente de Vision…", flush=True)
    ok = await attendre(lambda: serveur.players and serveur.players[0].connected, "Vision relié (HELO)", 120)
    if not ok:
        print("BANC KO", flush=True); return
    p = serveur.players[0]
    resultats = [ok]
    resultats.append(await attendre(lambda: "Vision" in (p.name or ""), f"nom reçu ({p.name})", 10))
    print("  codecs annoncés :", p.supported_codecs, flush=True)
    url1 = f"http://10.0.2.2:{PORT_HTTP}/a.wav"
    url2 = f"http://10.0.2.2:{PORT_HTTP}/b.wav"
    await p.play_url(url1, mime_type="audio/wav")
    resultats.append(await attendre(lambda: p.state == PlayerState.PLAYING, "lecture démarrée (STMs)"))
    await asyncio.sleep(1.5)
    resultats.append(await attendre(lambda: p.elapsed_milliseconds > 300, f"position qui avance ({p.elapsed_milliseconds} ms)"))
    await p.pause()
    resultats.append(await attendre(lambda: p.state == PlayerState.PAUSED, "pause confirmée (STMp)"))
    await p.play()
    resultats.append(await attendre(lambda: p.state == PlayerState.PLAYING, "reprise confirmée (STMr)"))
    await p.volume_set(30)
    await asyncio.sleep(0.5)
    # Le suivant, à la suite (comme Music Assistant après « décodeur prêt ») : il doit démarrer sans arrêt entre les deux.
    await attendre(lambda: p._decoder_ready, "décodeur prêt (STMd)", 30)
    await p.play_url(url2, mime_type="audio/wav", enqueue=True)
    resultats.append(await attendre(lambda: p.current_url == url2 and p.state == PlayerState.PLAYING, "titre suivant enchaîné", 40))
    resultats.append(await attendre(lambda: p.state == PlayerState.STOPPED, "fin de lecture (STMu)", 60))
    await serveur.stop()
    print("BANC OK" if all(resultats) else "BANC KO", flush=True)


asyncio.run(main())
