# Banc d'essai de Vision : Home Assistant de test (pytest-homeassistant-custom-component) sous Linux, comme en vrai.
#
#   docker build -t vision-banc:0.13.367-2 essai/banc
#   essai/banc/lancer.sh            (toute la suite, un seul pytest)
#
# Même base que l'image vision-banc:0.13.367 d'origine (Python 3.14.8, Debian trixie), plus les dépendances que
# l'intégration déclare dans son manifest.json (PyQRCode), qui manquaient : sans elles, les essais de l'appairage
# et du QR échouaient à l'import. Garder cette liste alignée sur "requirements" du manifeste.
FROM python:3.14.8-slim-trixie

RUN apt-get update \
 && apt-get install -y --no-install-recommends gcc g++ libffi-dev libturbojpeg0 ffmpeg git nodejs \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir pytest-homeassistant-custom-component==0.13.367 \
 && pip install --no-cache-dir PyQRCode==1.2.1

WORKDIR /src/essai
CMD ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider", "-o", "asyncio_mode=auto"]
