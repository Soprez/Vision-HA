"""Images des messages riches : types vérifiés par les octets, réduction, GIF → WebP animé, cache disque (1.94, point 4).

Tout ici est bloquant (Pillow, disque) : à appeler dans l'executor. Rien n'est jamais servi tel quel : une image venue
d'ailleurs (URL, Giphy) est décodée, réduite et réencodée, ce qui écarte aussi ce qui s'y cachait (métadonnées, scripts).

Poids et formats (docs de la conception, §1.11) :
- image de notification : 1280 px au plus, WebP qualité 80 ;
- GIF : WebP animé, 480 px de large au plus, 15 images par seconde au plus, 6 secondes au plus, plus une vignette fixe ;
- vignette : 640 px au plus.
"""
from __future__ import annotations

import hashlib
import io
import os
import time
from typing import Any

# Au-delà, on refuse de décoder (bombe de décompression) : 40 millions de pixels.
PIXELS_MAX = 40_000_000
NOTIFICATION_PX = 1280
VIGNETTE_PX = 640
GIF_LARGEUR_PX = 480
GIF_FPS_MAX = 15
GIF_DUREE_MAX_S = 6.0
QUALITE = 80


class ImageRefusee(ValueError):
    """L'image n'est pas acceptée : type inconnu, trop grande, illisible."""


def detecter(octets: bytes) -> str | None:
    """Le type MIME d'après les premiers octets (JPEG, PNG, WebP, GIF) ; None si autre chose. Jamais d'après l'extension."""
    if octets[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if octets[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if octets[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if octets[:4] == b"RIFF" and octets[8:12] == b"WEBP":
        return "image/webp"
    return None


def _ouvrir(octets: bytes, types: tuple[str, ...] = ("image/jpeg", "image/png", "image/webp", "image/gif")):
    from PIL import Image

    mime = detecter(octets)
    if mime not in types:
        raise ImageRefusee("type")
    try:
        im = Image.open(io.BytesIO(octets))
        largeur, hauteur = im.size
    except Exception as err:  # noqa: BLE001 - Pillow lève des erreurs de plusieurs sortes sur un fichier abîmé
        raise ImageRefusee("illisible") from err
    if largeur <= 0 or hauteur <= 0 or largeur * hauteur > PIXELS_MAX:
        raise ImageRefusee("trop_grande")
    return im


def _reduite(im, max_px: int):
    from PIL import Image

    largeur, hauteur = im.size
    plus_grand = max(largeur, hauteur)
    if plus_grand <= max_px:
        return im
    echelle = max_px / plus_grand
    return im.resize((max(1, round(largeur * echelle)), max(1, round(hauteur * echelle))), Image.LANCZOS)


def _webp(im, qualite: int = QUALITE) -> bytes:
    sortie = io.BytesIO()
    im.save(sortie, format="WEBP", quality=qualite, method=4)
    return sortie.getvalue()


def image_vers_webp(octets: bytes, max_px: int = NOTIFICATION_PX) -> tuple[bytes, str]:
    """Une image fixe (JPEG, PNG, WebP) réduite à `max_px` et recodée en WebP. Un GIF passe par `gif_vers_webp`."""
    im = _ouvrir(octets, ("image/jpeg", "image/png", "image/webp"))
    im.seek(0)
    im = _reduite(im.convert("RGBA" if im.mode in ("RGBA", "LA", "P") else "RGB"), max_px)
    return _webp(im), "image/webp"


def vignette(octets: bytes, max_px: int = VIGNETTE_PX) -> bytes:
    """La première image, réduite, en WebP fixe (JPEG, PNG, WebP ou GIF)."""
    im = _ouvrir(octets)
    im.seek(0)
    return _webp(_reduite(im.convert("RGBA" if im.mode in ("RGBA", "LA", "P") else "RGB"), max_px))


def gif_vers_webp(octets: bytes, largeur_max: int = GIF_LARGEUR_PX, fps_max: int = GIF_FPS_MAX,
                  duree_max_s: float = GIF_DUREE_MAX_S) -> tuple[bytes, bytes, dict[str, Any]]:
    """Un GIF (animé ou non) → (WebP animé, vignette WebP fixe, infos).

    Le même nombre d'images au plus que `fps_max` × `duree_max_s` ; au-delà, coupé. Les images trop rapprochées
    (plus de `fps_max` par seconde) sont fusionnées : leur durée s'ajoute à l'image gardée.
    """
    from PIL import ImageSequence

    im = _ouvrir(octets, ("image/gif",))
    pas_min_ms = 1000 // max(1, fps_max)
    images: list[Any] = []
    durees: list[int] = []
    total_ms = 0
    coupe = False
    for trame in ImageSequence.Iterator(im):
        d = max(20, int(trame.info.get("duration", 100) or 100))
        if total_ms + d > duree_max_s * 1000 and images:
            coupe = True
            break
        rgba = _reduite(trame.convert("RGBA"), largeur_max)
        # Une image qui arrive trop vite après la précédente : on prolonge la précédente.
        if images and durees[-1] < pas_min_ms:
            durees[-1] += d
        else:
            images.append(rgba.copy())
            durees.append(d)
        total_ms += d
    if not images:
        raise ImageRefusee("illisible")
    sortie = io.BytesIO()
    if len(images) == 1:
        images[0].save(sortie, format="WEBP", quality=QUALITE, method=4)
    else:
        images[0].save(sortie, format="WEBP", save_all=True, append_images=images[1:], duration=durees, loop=0,
                       quality=QUALITE, method=4)
    return sortie.getvalue(), _webp(images[0]), {"images": len(images), "duree_ms": sum(durees), "coupe": coupe,
                                                  "largeur": images[0].size[0], "hauteur": images[0].size[1]}


# ---------------------------------------------------------------- Le cache disque


class CacheDisque:
    """Un dossier de fichiers nommés par une clé, borné en octets et en âge ; les moins récemment lus partent d'abord."""

    def __init__(self, dossier: str, max_octets: int, duree_max_s: float | None = None) -> None:
        self.dossier = dossier
        self.max_octets = max_octets
        self.duree_max_s = duree_max_s

    @staticmethod
    def nom(cle: str, extension: str = "") -> str:
        return hashlib.sha1(cle.encode("utf-8")).hexdigest() + extension

    def chemin(self, cle: str, extension: str = "") -> str:
        return os.path.join(self.dossier, self.nom(cle, extension))

    def lire(self, cle: str, extension: str = "") -> str | None:
        """Le chemin du fichier gardé, ou None ; lire le rajeunit (c'est la dernière lecture qui compte)."""
        chemin = self.chemin(cle, extension)
        try:
            age = time.time() - os.path.getmtime(chemin)
        except OSError:
            return None
        if self.duree_max_s is not None and age > self.duree_max_s:
            self._retirer(chemin)
            return None
        try:
            os.utime(chemin, None)
        except OSError:
            pass
        return chemin

    def ecrire(self, cle: str, octets: bytes, extension: str = "") -> str:
        os.makedirs(self.dossier, exist_ok=True)
        chemin = self.chemin(cle, extension)
        temporaire = chemin + ".tmp"
        with open(temporaire, "wb") as f:
            f.write(octets)
        os.replace(temporaire, chemin)
        self.purger()
        return chemin

    @staticmethod
    def _retirer(chemin: str) -> None:
        try:
            os.remove(chemin)
        except OSError:
            pass

    def contenu(self) -> list[tuple[str, int, float]]:
        """(chemin, octets, dernière lecture) de chaque fichier du cache."""
        sortie = []
        try:
            noms = os.listdir(self.dossier)
        except OSError:
            return sortie
        for nom in noms:
            chemin = os.path.join(self.dossier, nom)
            try:
                st = os.stat(chemin)
            except OSError:
                continue
            if nom.endswith(".tmp"):
                if time.time() - st.st_mtime > 3600:
                    self._retirer(chemin)
                continue
            sortie.append((chemin, st.st_size, st.st_mtime))
        return sortie

    def taille(self) -> int:
        return sum(o for _, o, _ in self.contenu())

    def purger(self) -> int:
        """Retire les fichiers trop vieux, puis les moins récemment lus jusqu'à tenir dans `max_octets`. Rend le nombre retiré."""
        retires = 0
        maintenant = time.time()
        gardes = []
        for chemin, octets, lu in self.contenu():
            if self.duree_max_s is not None and maintenant - lu > self.duree_max_s:
                self._retirer(chemin); retires += 1
            else:
                gardes.append((lu, chemin, octets))
        total = sum(o for _, _, o in gardes)
        for lu, chemin, octets in sorted(gardes):
            if total <= self.max_octets:
                break
            self._retirer(chemin); retires += 1
            total -= octets
        return retires
