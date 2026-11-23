// ============================================================================
// CAPTEURS : GPS, Boussole magnétique, Baromètre (QNH / FL) & Enregistreur GPX
// ============================================================================

const SensorState = {
  lat: null,
  lon: null,
  accuracyM: null,
  groundSpeedKt: null,
  gpsTrackDeg: null,
  compassTrueDeg: null,
  compassMagDeg: null,
  compassAccuracy: 0,
  activeHeadingDeg: null,
  headingSource: 'NONE',
  altitudeMslFt: null,
  baroPressureHpa: null,
  baroQnhFt: null,
  flightLevel: null,
  qnhHpa: 1013,
  orientationMode: 'track-up', // 'track-up' | 'north-up'
  tracePoints: [],
  lastTraceTs: 0
};

function chooseActiveHeading(speedKt, gpsTrack, compassDeg) {
  // Au-dessus de 8 kt, la route sol GPS est insensible aux perturbations magnétiques cockpit
  if (Number.isFinite(speedKt) && speedKt >= 8 && Number.isFinite(gpsTrack)) {
    return { heading: gpsTrack, source: 'GPS' };
  }
  if (Number.isFinite(compassDeg)) {
    return { heading: compassDeg, source: 'BOUSSOLE' };
  }
  if (Number.isFinite(gpsTrack)) {
    return { heading: gpsTrack, source: 'GPS' };
  }
  return { heading: null, source: 'ATTENTE' };
}

function recomputeBaroAltitudes() {
  if (!Number.isFinite(SensorState.baroPressureHpa)) {
    SensorState.baroQnhFt = null;
    SensorState.flightLevel = null;
    return;
  }
  const flAlt = pressureToAltitudeFt(SensorState.baroPressureHpa, 1013.25);
  SensorState.flightLevel = Number.isFinite(flAlt) ? Math.round(flAlt / 100) : null;
  SensorState.baroQnhFt = pressureToAltitudeFt(SensorState.baroPressureHpa, SensorState.qnhHpa);
}

function updateHudTelemetry() {
  const gsEl = document.getElementById('hud-gs');
  const altEl = document.getElementById('hud-alt');
  const altSrcEl = document.getElementById('hud-alt-src');
  const flEl = document.getElementById('hud-fl');
  const trkEl = document.getElementById('hud-trk');
  const modeEl = document.getElementById('hud-mode');
  const qnhBtn = document.getElementById('btn-qnh');

  if (gsEl) {
    gsEl.textContent = Number.isFinite(SensorState.groundSpeedKt)
      ? `${Math.round(SensorState.groundSpeedKt)} kt`
      : '-- kt';
  }

  const useBaro = Number.isFinite(SensorState.baroQnhFt);
  const chosenAlt = useBaro ? SensorState.baroQnhFt : SensorState.altitudeMslFt;

  if (altSrcEl) {
    altSrcEl.textContent = useBaro ? 'BARO' : 'GPS';
  }
  if (altEl) {
    altEl.textContent = Number.isFinite(chosenAlt)
      ? `${Math.round(chosenAlt)} ft`
      : '-- ft';
  }
  if (flEl) {
    const flStr = Number.isFinite(SensorState.flightLevel)
      ? `FL ${String(Math.max(0, SensorState.flightLevel)).padStart(3, '0')}`
      : 'FL ---';
    flEl.textContent = `${flStr} · QNH ${SensorState.qnhHpa}`;
  }
  if (qnhBtn) {
    qnhBtn.textContent = `QNH: ${SensorState.qnhHpa}`;
  }

  const { heading, source } = chooseActiveHeading(
    SensorState.groundSpeedKt,
    SensorState.gpsTrackDeg,
    SensorState.compassTrueDeg
  );
  SensorState.activeHeadingDeg = heading;
  SensorState.headingSource = source;

  if (trkEl) {
    trkEl.textContent = Number.isFinite(heading)
      ? `${String(Math.round(heading)).padStart(3, '0')}°`
      : '--°';
  }
  if (modeEl) {
    modeEl.textContent = `${source} · ${SensorState.orientationMode.toUpperCase()}`;
  }
}

function recordTracePoint(lat, lon, altFt) {
  const now = Date.now();
  if (now - SensorState.lastTraceTs < 2000) return;
  SensorState.lastTraceTs = now;
  SensorState.tracePoints.push({
    lat,
    lon,
    altM: Number.isFinite(altFt) ? altFt / M_TO_FT : 0,
    iso: new Date(now).toISOString()
  });
  if (SensorState.tracePoints.length > 20000) {
    SensorState.tracePoints.shift();
  }
}

function exportTraceAsGpx() {
  if (SensorState.tracePoints.length === 0) {
    alert('Aucun point GPS enregistré pour le moment.');
    return;
  }
  const trkpts = SensorState.tracePoints
    .map(
      (p) =>
        `      <trkpt lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}"><ele>${p.altM.toFixed(1)}</ele><time>${p.iso}</time></trkpt>`
    )
    .join('\n');

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="VFR Nav Mobile" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Vol VFR ${new Date().toISOString().slice(0, 16)}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>`;

  const blob = new Blob([gpx], { type: 'application/gpx+xml' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `trace_vfr_${new Date().toISOString().slice(0, 10)}.gpx`;
  a.click();
  URL.revokeObjectURL(url);
}

// Ponts appelés directement par Android MainActivity.java
window.onNativeLocation = function (lat, lon, speedMps, bearingDeg, altMeters, accuracyM) {
  SensorState.lat = lat;
  SensorState.lon = lon;
  SensorState.accuracyM = accuracyM >= 0 ? accuracyM : null;
  if (speedMps >= 0) {
    const rawKt = mpsToKnots(speedMps);
    SensorState.groundSpeedKt =
      SensorState.groundSpeedKt == null ? rawKt : SensorState.groundSpeedKt * 0.65 + rawKt * 0.35;
  }
  if (bearingDeg >= 0) {
    SensorState.gpsTrackDeg = bearingDeg;
  }
  if (altMeters !== null && Number.isFinite(altMeters)) {
    SensorState.altitudeMslFt = metersToFeet(altMeters);
  }
  const altForLog = Number.isFinite(SensorState.baroQnhFt)
    ? SensorState.baroQnhFt
    : SensorState.altitudeMslFt;
  recordTracePoint(lat, lon, altForLog);
  updateHudTelemetry();
  if (window.onTelemetryUpdated) window.onTelemetryUpdated();
};

window.onNativeCompass = function (trueAzimuthDeg, magAzimuthDeg, accuracy) {
  SensorState.compassTrueDeg = trueAzimuthDeg;
  SensorState.compassMagDeg = magAzimuthDeg;
  SensorState.compassAccuracy = accuracy;
  updateHudTelemetry();
  if (window.onTelemetryUpdated) window.onTelemetryUpdated();
};

window.onNativeBarometer = function (hpa) {
  SensorState.baroPressureHpa =
    SensorState.baroPressureHpa == null ? hpa : SensorState.baroPressureHpa * 0.8 + hpa * 0.2;
  recomputeBaroAltitudes();
  updateHudTelemetry();
};

function startSensors() {
  // Secours Web Geolocation si lancé hors APK Android
  if ('geolocation' in navigator) {
    navigator.geolocation.watchPosition(
      (pos) => {
        const c = pos.coords;
        window.onNativeLocation(
          c.latitude,
          c.longitude,
          Number.isFinite(c.speed) ? c.speed : -1,
          Number.isFinite(c.heading) ? c.heading : -1,
          Number.isFinite(c.altitude) ? c.altitude : null,
          Number.isFinite(c.accuracy) ? c.accuracy : -1
        );
      },
      () => {},
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 10000 }
    );
  }

  window.addEventListener(
    'deviceorientationabsolute',
    (ev) => {
      if (typeof ev.alpha === 'number') {
        const az = (360 - ev.alpha + 360) % 360;
        window.onNativeCompass(az, az, 2);
      }
    },
    true
  );

  updateHudTelemetry();
}
