/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <algorithm>
#include <cmath>
#include "fpssync.h"
#include "log.h"

namespace libretrodroid {

unsigned FPSSync::advanceFrames() {
    // RomCloud : écran 2, 3 ou 4 fois plus rapide que le jeu : une image du jeu tous les
    // [vsyncInterval] rafraîchissements (0 : dernière image redessinée), affichées régulièrement.
    if (useVSync) return vsyncCount++ % vsyncInterval == 0 ? 1 : 0;

    if (lastFrame == MIN_TIME) {
        start();
    }

    auto now = std::chrono::steady_clock::now();
    auto frames = std::max((now - lastFrame) / sampleInterval, (long long) 1);
    lastFrame = lastFrame + sampleInterval * frames;

    // RomCloud : retard passager (une image plus longue) : pas d'image sautée, le jeu reprend au
    // rythme normal (le son se recale) ; images sautées seulement si l'appareil reste trop lent.
    lateRatio = lateRatio * 0.95 + (frames > 1 ? 0.05 : 0.0);
    if (frames > 1 && lateRatio < 0.3) frames = 1;

    return frames;
}

unsigned FPSSync::computeInterval(double contentRefreshRate, double screenRefreshRate) {
    if (contentRefreshRate <= 0 || screenRefreshRate <= 0) return 0;
    // Écart de moins de 5 Hz côté écran (tolérance de LibretroDroid), rattrapé par la vitesse du son.
    for (unsigned interval = 1; interval <= 4; interval++) {
        if (std::abs(contentRefreshRate * interval - screenRefreshRate) < 5) return interval;
    }
    return 0;
}

FPSSync::FPSSync(double contentRefreshRate, double screenRefreshRate) {
    this->contentRefreshRate = contentRefreshRate;
    this->screenRefreshRate = screenRefreshRate;
    const unsigned interval = computeInterval(contentRefreshRate, screenRefreshRate);
    this->useVSync = interval > 0;
    this->vsyncInterval = std::max(interval, 1u);
    this->sampleInterval = std::chrono::microseconds((long) ((1000000L / contentRefreshRate)));
    reset();
}

void FPSSync::start() {
    LOGI("Starting game with fps %f on a screen with refresh rate %f. Using vsync: %d (interval %u)", contentRefreshRate, screenRefreshRate, useVSync, vsyncInterval);
    lastFrame = std::chrono::steady_clock::now();
}

void FPSSync::reset() {
    lastFrame = MIN_TIME;
    vsyncCount = 0;
    lateRatio = 0;
}

void FPSSync::updateScreenRefreshRate(double rate) {
    const unsigned interval = computeInterval(contentRefreshRate, rate);
    const bool vsync = interval > 0;
    screenRefreshRate = rate;
    if (vsync == useVSync && std::max(interval, 1u) == vsyncInterval) return;
    useVSync = vsync;
    vsyncInterval = std::max(interval, 1u);
    reset();
    LOGI("Screen refresh rate is now %f for a game at %f fps. Using vsync: %d (interval %u)", rate, contentRefreshRate, useVSync, vsyncInterval);
}

double FPSSync::getTimeStretchFactor() {
    // RomCloud : jeu au rythme de l'écran : son produit plus ou moins vite d'autant (il était
    // inversé : écart doublé, laissé à la correction de la file audio).
    return useVSync ? screenRefreshRate / vsyncInterval / contentRefreshRate : 1.0;
}

void FPSSync::wait() {
    if (useVSync) return;
    std::this_thread::sleep_until(lastFrame);
}

} //namespace libretrodroid
