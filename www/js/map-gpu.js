// ============================================================================
// MOTEUR CARTOGRAPHIQUE GPU (MAPLIBRE GL JS) & ORCHESTRATION INTERFACE COCKPIT
// ============================================================================

const OaciMapState = {
  map: null,
  pdfWidth: 4000,
  pdfHeight: 4000,
  hasOaciPdf: false,
  calibration: null,
  gcps: [],
  oaciCalibActive: false,
  followGps: true,
  selectedFeature: null,
  renderCurrent: null // { lat, lon, heading }
};

function initOaciCalibration() {
  try {
    const saved = localStorage.getItem('vfr_oaci_calib_v1');
    if (saved) {
      const parsed = JSON.parse(saved);
      if (parsed.calibration) OaciMapState.calibration = parsed.calibration;
      if (Array.isArray(parsed.gcps)) OaciMapState.gcps = parsed.gcps;
    }
  } catch (_) {}

  // Calibration par défaut centrée sur la France (Ouest / Charente-Maritime) tant qu'aucun PDF n'est calibré
  if (!OaciMapState.calibration) {
    OaciMapState.calibration = solveConformalCalibration([
      { lat: 46.1792, lon: -1.1953, xPdf: 1800, yPdf: 1800, label: 'LFBH' },
      { lat: 45.8053, lon: -1.1244, xPdf: 1840, yPdf: 2220, label: 'LFJI' }
    ]);
  }
}

function saveOaciCalibration() {
  try {
    localStorage.setItem(
      'vfr_oaci_calib_v1',
      JSON.stringify({
        calibration: OaciMapState.calibration,
        gcps: OaciMapState.gcps
      })
    );
  } catch (_) {}
}

/** Convertit coordonnées PDF [0..W, 0..H] -> [lng, lat] MapLibre WebMercator */
function pdfToMapLngLat(xPdf, yPdf) {
  const S = Math.max(OaciMapState.pdfWidth, OaciMapState.pdfHeight);
  const u = Math.max(0, Math.min(1, xPdf / S));
  const v = Math.max(0.001, Math.min(0.999, yPdf / S));
  const lng = u * 360 - 180;
  const lat = (2 * Math.atan(Math.exp(Math.PI * (1 - 2 * v))) - Math.PI / 2) * RAD2DEG;
  return [lng, lat];
}

/** Convertit [lng, lat] MapLibre -> coordonnées PDF [xPdf, yPdf] */
function mapLngLatToPdf(lng, lat) {
  const S = Math.max(OaciMapState.pdfWidth, OaciMapState.pdfHeight);
  const u = (lng + 180) / 360;
  const sinLat = Math.max(-0.9999, Math.min(0.9999, Math.sin(lat * DEG2RAD)));
  const v = 0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI);
  return [u * S, v * S];
}

/** Conversion directe WGS84 (lat, lon) -> MapLibre [lng, lat] via Lambert + calibration */
function geoToMap(lat, lon) {
  const [xPdf, yPdf] = forwardCalibration(OaciMapState.calibration, lat, lon);
  return pdfToMapLngLat(xPdf, yPdf);
}

/** Conversion inverse MapLibre [lng, lat] -> WGS84 (lat, lon) */
function mapToGeo(lng, lat) {
  const [xPdf, yPdf] = mapLngLatToPdf(lng, lat);
  return inverseCalibration(OaciMapState.calibration, xPdf, yPdf);
}

function buildAirfieldsGeoJson() {
  return {
    type: 'FeatureCollection',
    features: AiracManager.airfields.map((af) => ({
      type: 'Feature',
      properties: {
        icao: af.icao,
        name: af.name,
        freq: af.freq,
        rwy: af.rwy,
        elevFt: af.elevFt,
        lat: af.lat,
        lon: af.lon
      },
      geometry: {
        type: 'Point',
        coordinates: geoToMap(af.lat, af.lon)
      }
    }))
  };
}

function buildGraticuleGeoJson() {
  const features = [];
  for (let lat = 42; lat <= 51; lat += 1) {
    const coords = [];
    for (let lon = -5; lon <= 8; lon += 0.5) coords.push(geoToMap(lat, lon));
    features.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: coords } });
  }
  for (let lon = -5; lon <= 8; lon += 1) {
    const coords = [];
    for (let lat = 42; lat <= 51; lat += 0.5) coords.push(geoToMap(lat, lon));
    features.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: coords } });
  }
  return { type: 'FeatureCollection', features };
}

window.refreshRouteOnMap = function () {
  const map = OaciMapState.map;
  if (!map || !map.getSource('route-src')) return;

  const wps = FlightPlanManager.waypoints;
  const lineCoords = [];
  if (Number.isFinite(SensorState.lat) && Number.isFinite(SensorState.lon) && wps.length > 0) {
    lineCoords.push(geoToMap(SensorState.lat, SensorState.lon));
  }
  for (const wp of wps) {
    lineCoords.push(geoToMap(wp.lat, wp.lon));
  }

  const features = [];
  if (lineCoords.length >= 2) {
    features.push({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: lineCoords }
    });
  }
  wps.forEach((wp, idx) => {
    features.push({
      type: 'Feature',
      properties: { name: wp.name, idx },
      geometry: { type: 'Point', coordinates: geoToMap(wp.lat, wp.lon) }
    });
  });

  map.getSource('route-src').setData({ type: 'FeatureCollection', features });

  if (map.getSource('airspaces-src') && FlightPlanManager.airspacesGeoJson) {
    const mappedFeatures = (FlightPlanManager.airspacesGeoJson.features || []).map((f) => {
      if (f.geometry && f.geometry.type === 'Polygon') {
        const ring = f.geometry.coordinates[0].map(([lon, lat]) => geoToMap(lat, lon));
        return { ...f, geometry: { type: 'Polygon', coordinates: [ring] } };
      }
      return f;
    });
    map.getSource('airspaces-src').setData({ type: 'FeatureCollection', features: mappedFeatures });
  }
};

window.refreshVacOverlayOnMap = function () {
  const map = OaciMapState.map;
  if (!map) return;

  const cal = VacManager.calibrationsByPage[VacManager.activePage];
  const layerExists = !!map.getLayer('vac-layer');
  const srcExists = !!map.getSource('vac-src');

  if (!VacManager.visible || !cal || VacManager.pageCount === 0) {
    if (layerExists) map.setLayoutProperty('vac-layer', 'visibility', 'none');
    return;
  }

  const coords = [
    geoToMap(cal.topLeft[0], cal.topLeft[1]),
    geoToMap(cal.topRight[0], cal.topRight[1]),
    geoToMap(cal.bottomRight[0], cal.bottomRight[1]),
    geoToMap(cal.bottomLeft[0], cal.bottomLeft[1])
  ];

  const imgUrl = `https://appassets.androidplatform.net/vac/${VacManager.vacId}/page_${VacManager.activePage}.png?w=2048&t=${Date.now()}`;

  if (srcExists) {
    map.getSource('vac-src').updateImage({ url: imgUrl, coordinates: coords });
  } else {
    map.addSource('vac-src', {
      type: 'image',
      url: imgUrl,
      coordinates: coords
    });
    map.addLayer(
      {
        id: 'vac-layer',
        type: 'raster',
        source: 'vac-src',
        paint: {
          'raster-opacity': VacManager.opacity,
          'raster-fade-duration': 0
        }
      },
      'airfields-circle'
    );
  }

  if (map.getLayer('vac-layer')) {
    map.setLayoutProperty('vac-layer', 'visibility', 'visible');
    map.setPaintProperty('vac-layer', 'raster-opacity', VacManager.opacity);
  }
};

function createAircraftArrowImage() {
  const size = 64;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');

  ctx.translate(size / 2, size / 2);
  ctx.fillStyle = '#38bdf8';
  ctx.strokeStyle = '#090e1a';
  ctx.lineWidth = 3;

  // Silhouette avion vue de dessus
  ctx.beginPath();
  ctx.moveTo(0, -24);
  ctx.lineTo(5, -8);
  ctx.lineTo(24, 2);
  ctx.lineTo(24, 7);
  ctx.lineTo(5, 3);
  ctx.lineTo(4, 16);
  ctx.lineTo(12, 22);
  ctx.lineTo(12, 25);
  ctx.lineTo(0, 22);
  ctx.lineTo(-12, 25);
  ctx.lineTo(-12, 22);
  ctx.lineTo(-4, 16);
  ctx.lineTo(-5, 3);
  ctx.lineTo(-24, 7);
  ctx.lineTo(-24, 2);
  ctx.lineTo(-5, -8);
  ctx.closePath();
  ctx.fill();
  ctx.stroke();

  return ctx.getImageData(0, 0, size, size);
}

function initGpuMap() {
  if (!window.maplibregl) {
    console.error('MapLibre GL JS non chargé.');
    return;
  }

  // Vérifie si un PDF OACI est déjà présent sur le stockage Android
  if (window.VfrNative && typeof window.VfrNative.getOaciDimensionsJson === 'function') {
    const dimJson = window.VfrNative.getOaciDimensionsJson();
    if (dimJson) {
      try {
        const dims = JSON.parse(dimJson);
        if (dims.width > 0 && dims.height > 0) {
          OaciMapState.pdfWidth = dims.width;
          OaciMapState.pdfHeight = dims.height;
          OaciMapState.hasOaciPdf = true;
        }
      } catch (_) {}
    }
  }

  const startCenter = geoToMap(45.8053, -1.1244); // Marennes LFJI

  const map = new maplibregl.Map({
    container: 'map',
    attributionControl: false,
    center: startCenter,
    zoom: 3.5,
    minZoom: 0,
    maxZoom: 8,
    maxPitch: 0,
    style: {
      version: 8,
      sources: {
        oaci: {
          type: 'raster',
          tiles: ['https://appassets.androidplatform.net/tiles/oaci/{z}/{x}_{y}.webp'],
          tileSize: 256,
          minzoom: 0,
          maxzoom: 6
        },
        graticule: { type: 'geojson', data: buildGraticuleGeoJson() },
        'airspaces-src': { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
        'airfields-src': { type: 'geojson', data: buildAirfieldsGeoJson() },
        'route-src': { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
        'aircraft-src': { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
        'track-vec-src': { type: 'geojson', data: { type: 'FeatureCollection', features: [] } }
      },
      layers: [
        {
          id: 'graticule-line',
          type: 'line',
          source: 'graticule',
          paint: { 'line-color': '#1e293b', 'line-width': 1 }
        },
        {
          id: 'oaci-layer',
          type: 'raster',
          source: 'oaci',
          layout: { visibility: OaciMapState.hasOaciPdf ? 'visible' : 'none' },
          paint: { 'raster-fade-duration': 0, 'raster-resampling': 'linear' }
        },
        {
          id: 'airspaces-fill',
          type: 'fill',
          source: 'airspaces-src',
          paint: {
            'fill-color': ['coalesce', ['get', 'color'], '#ef4444'],
            'fill-opacity': 0.18
          }
        },
        {
          id: 'airspaces-outline',
          type: 'line',
          source: 'airspaces-src',
          paint: {
            'line-color': ['coalesce', ['get', 'color'], '#ef4444'],
            'line-width': 2
          }
        },
        {
          id: 'airfields-circle',
          type: 'circle',
          source: 'airfields-src',
          paint: {
            'circle-radius': 7,
            'circle-color': '#0284c7',
            'circle-stroke-width': 2,
            'circle-stroke-color': '#ffffff'
          }
        },
        {
          id: 'route-casing',
          type: 'line',
          source: 'route-src',
          filter: ['==', '$type', 'LineString'],
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': '#ffffff', 'line-width': 6 }
        },
        {
          id: 'route-line',
          type: 'line',
          source: 'route-src',
          filter: ['==', '$type', 'LineString'],
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': '#ec4899', 'line-width': 3.5 }
        },
        {
          id: 'route-points',
          type: 'circle',
          source: 'route-src',
          filter: ['==', '$type', 'Point'],
          paint: {
            'circle-radius': 6,
            'circle-color': '#ec4899',
            'circle-stroke-width': 2,
            'circle-stroke-color': '#ffffff'
          }
        },
        {
          id: 'track-vector',
          type: 'line',
          source: 'track-vec-src',
          paint: {
            'line-color': '#22c55e',
            'line-width': 2.5,
            'line-dasharray': [2, 2]
          }
        },
        {
          id: 'aircraft-symbol',
          type: 'symbol',
          source: 'aircraft-src',
          layout: {
            'icon-image': 'aircraft-icon',
            'icon-size': 0.72,
            'icon-rotate': ['get', 'bearing'],
            'icon-rotation-alignment': 'map',
            'icon-allow-overlap': true
          }
        }
      ]
    }
  });

  OaciMapState.map = map;

  map.on('load', () => {
    map.addImage('aircraft-icon', createAircraftArrowImage());
    window.refreshRouteOnMap();
    window.refreshVacOverlayOnMap();
    startGpuRenderLoop();
  });

  // Désactive le suivi automatique dès que le pilote glisse la carte au doigt
  map.on('dragstart', () => {
    OaciMapState.followGps = false;
  });

  // Clic sur un aérodrome
  map.on('click', 'airfields-circle', (e) => {
    if (!e.features || !e.features.length) return;
    const props = e.features[0].properties;
    openFeaturePopup({
      type: 'AIRFIELD',
      name: `${props.icao} — ${props.name}`,
      icao: props.icao,
      lat: Number(props.lat),
      lon: Number(props.lon),
      freq: props.freq,
      details: `Fréq : <strong>${props.freq} MHz</strong><br/>Piste : ${props.rwy}<br/>Alt : ${props.elevFt} ft`
    });
  });

  // Clic sur une zone / polygone importé
  map.on('click', 'airspaces-fill', (e) => {
    if (!e.features || !e.features.length) return;
    const props = e.features[0].properties;
    openFeaturePopup({
      type: 'ZONE',
      name: props.name || 'Zone réglementée',
      lat: e.lngLat.lat,
      lon: e.lngLat.lng,
      details: `Plancher/Plafond : <strong>${props.floor || 'SFC'} — ${props.ceiling || '--'}</strong><br/>${props.summary || ''}`
    });
  });
}

function startGpuRenderLoop() {
  const map = OaciMapState.map;
  let lastTs = performance.now();

  function frame(now) {
    const dt = Math.min(0.1, (now - lastTs) / 1000);
    lastTs = now;

    if (Number.isFinite(SensorState.lat) && Number.isFinite(SensorState.lon)) {
      const targetHeading = Number.isFinite(SensorState.activeHeadingDeg)
        ? SensorState.activeHeadingDeg
        : 0;

      if (!OaciMapState.renderCurrent) {
        OaciMapState.renderCurrent = {
          lat: SensorState.lat,
          lon: SensorState.lon,
          heading: targetHeading
        };
      }

      const alpha = 1 - Math.exp(-9 * dt);
      OaciMapState.renderCurrent.lat += (SensorState.lat - OaciMapState.renderCurrent.lat) * alpha;
      OaciMapState.renderCurrent.lon += (SensorState.lon - OaciMapState.renderCurrent.lon) * alpha;

      const deltaH = ((targetHeading - OaciMapState.renderCurrent.heading + 540) % 360) - 180;
      OaciMapState.renderCurrent.heading =
        (OaciMapState.renderCurrent.heading + deltaH * alpha + 360) % 360;

      const mapCoord = geoToMap(OaciMapState.renderCurrent.lat, OaciMapState.renderCurrent.lon);

      // L'icône avion pointe selon le cap boussole (HDG) si dispo, sinon la route active
      const noseHdg = Number.isFinite(SensorState.compassTrueDeg)
        ? SensorState.compassTrueDeg
        : OaciMapState.renderCurrent.heading;

      const acSrc = map.getSource('aircraft-src');
      if (acSrc) {
        acSrc.setData({
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              properties: { bearing: noseHdg },
              geometry: { type: 'Point', coordinates: mapCoord }
            }
          ]
        });
      }

      // Vecteur d'anticipation 5 minutes selon la route sol GPS (TRK)
      const vecSrc = map.getSource('track-vec-src');
      if (vecSrc && Number.isFinite(SensorState.groundSpeedKt) && SensorState.groundSpeedKt > 5) {
        const track = Number.isFinite(SensorState.gpsTrackDeg)
          ? SensorState.gpsTrackDeg
          : OaciMapState.renderCurrent.heading;
        const dist5MinNm = (SensorState.groundSpeedKt * 5) / 60;
        const [endLat, endLon] = destinationPoint(
          OaciMapState.renderCurrent.lat,
          OaciMapState.renderCurrent.lon,
          track,
          dist5MinNm
        );
        vecSrc.setData({
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: { type: 'LineString', coordinates: [mapCoord, geoToMap(endLat, endLon)] }
            }
          ]
        });
      }

      if (OaciMapState.followGps) {
        const bearing =
          SensorState.orientationMode === 'track-up' ? OaciMapState.renderCurrent.heading : 0;
        map.jumpTo({ center: mapCoord, bearing });
      }
    }

    requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);
}

function openFeaturePopup(feat) {
  OaciMapState.selectedFeature = feat;
  const popup = document.getElementById('feature-popup');
  const titleEl = document.getElementById('popup-title');
  const bodyEl = document.getElementById('popup-body');
  const vacBtn = document.getElementById('popup-load-vac');

  if (titleEl) titleEl.textContent = feat.name;
  if (bodyEl) bodyEl.innerHTML = feat.details;
  if (vacBtn) {
    vacBtn.classList.toggle('hidden', feat.type !== 'AIRFIELD');
  }
  if (popup) popup.classList.remove('hidden');
}

// Callbacks natifs import PDF
window.onOaciPdfImported = function (ok, width, height) {
  if (!ok) {
    alert("Erreur lors de l'import du PDF OACI.");
    return;
  }
  OaciMapState.pdfWidth = width;
  OaciMapState.pdfHeight = height;
  OaciMapState.hasOaciPdf = true;

  const map = OaciMapState.map;
  if (map) {
    map.setLayoutProperty('oaci-layer', 'visibility', 'visible');
    const src = map.getSource('oaci');
    if (src && typeof src.reload === 'function') src.reload();
  }
  alert(`Carte OACI chargée (${width}×${height} px). Utilise "Calibrer OACI" si tu dois ajuster les repères.`);
};

window.onVacPdfImported = async function (ok, metaJsonStr) {
  if (!ok || !metaJsonStr) {
    alert("Erreur lors de l'import de la carte VAC.");
    return;
  }
  const meta = JSON.parse(metaJsonStr);
  await VacManager.analyzeAndAutoCalibrate(meta);
};

window.onTelemetryUpdated = function () {
  FlightPlanManager.updateNavigationProgress();
  window.refreshRouteOnMap();
};

// ============================================================================
// INITIALISATION COMPLÈTE AU CHARGEMENT DU DOM
// ============================================================================
document.addEventListener('DOMContentLoaded', () => {
  initOaciCalibration();
  AiracManager.init();
  VacManager.init();
  FlightPlanManager.init();
  initGpuMap();
  startSensors();

  // Onglets du tiroir
  document.querySelectorAll('.tab-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const target = btn.dataset.tab;
      document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
      btn.classList.add('active');
      const panel = document.getElementById(`tab-${target}`);
      if (panel) panel.classList.add('active');
    });
  });

  // Bascule Track-Up / North-Up
  const toggleOrientation = () => {
    SensorState.orientationMode =
      SensorState.orientationMode === 'track-up' ? 'north-up' : 'track-up';
    const orientBtn = document.getElementById('btn-orient');
    if (orientBtn) {
      orientBtn.textContent =
        SensorState.orientationMode === 'track-up' ? 'Cap: Track-Up' : 'Cap: North-Up';
    }
    if (SensorState.orientationMode === 'north-up' && OaciMapState.map) {
      OaciMapState.map.easeTo({ bearing: 0, duration: 250 });
    }
    updateHudTelemetry();
  };
  document.getElementById('btn-orient')?.addEventListener('click', toggleOrientation);
  document.getElementById('hud-hdg-cell')?.addEventListener('click', toggleOrientation);

  // Réglage rapide QNH
  const promptQnh = () => {
    const val = prompt('Saisir le QNH local (en hPa) :', String(SensorState.qnhHpa));
    const parsed = parseInt(val, 10);
    if (Number.isFinite(parsed) && parsed >= 940 && parsed <= 1060) {
      SensorState.qnhHpa = parsed;
      recomputeBaroAltitudes();
      updateHudTelemetry();
    }
  };
  document.getElementById('btn-qnh')?.addEventListener('click', promptQnh);
  document.getElementById('hud-alt-cell')?.addEventListener('click', promptQnh);

  // Thèmes Nuit / Soleil / Rouge Cockpit
  const themes = ['dark', 'sun', 'red'];
  const themeLabels = { dark: 'Thème: Nuit', sun: 'Thème: Soleil', red: 'Thème: Rouge' };
  const appEl = document.getElementById('app');
  const themeBtn = document.getElementById('btn-theme');
  themeBtn?.addEventListener('click', () => {
    const cur = appEl.dataset.theme || 'dark';
    const next = themes[(themes.indexOf(cur) + 1) % themes.length];
    appEl.dataset.theme = next;
    themeBtn.textContent = themeLabels[next];
  });

  // Recentrer GPS
  document.getElementById('btn-center-gps')?.addEventListener('click', () => {
    OaciMapState.followGps = true;
    if (OaciMapState.map && Number.isFinite(SensorState.lat)) {
      OaciMapState.map.easeTo({ center: geoToMap(SensorState.lat, SensorState.lon), duration: 300 });
    }
  });

  // Verrouillage tactile turbulences
  document.getElementById('btn-lock-screen')?.addEventListener('click', () => {
    document.getElementById('lock-overlay')?.classList.remove('hidden');
  });
  document.getElementById('btn-unlock-screen')?.addEventListener('click', () => {
    document.getElementById('lock-overlay')?.classList.add('hidden');
  });

  // Déroutement / Aérodromes proches
  document.getElementById('btn-nearest')?.addEventListener('click', () => {
    const modal = document.getElementById('nearest-modal');
    const listEl = document.getElementById('nearest-list');
    if (!modal || !listEl) return;

    const nearest = AiracManager.getNearestAirfields(SensorState.lat, SensorState.lon, 5);
    listEl.innerHTML = '';
    nearest.forEach((af) => {
      const distStr = Number.isFinite(af.distNm)
        ? `${String(Math.round(af.bearing)).padStart(3, '0')}° · ${af.distNm.toFixed(1)} NM`
        : 'GPS requis';
      const row = document.createElement('div');
      row.className = 'list-item';
      row.innerHTML = `<div><strong>${af.icao} — ${af.name}</strong><div class="muted-text">${distStr} · ${af.freq} MHz · ${af.rwy}</div></div>`;
      const dctBtn = document.createElement('button');
      dctBtn.className = 'btn-primary btn-sm';
      dctBtn.textContent = 'DCT';
      dctBtn.onclick = () => {
        FlightPlanManager.setDirectTo({
          name: af.icao,
          lat: af.lat,
          lon: af.lon,
          freq: af.freq
        });
        modal.classList.add('hidden');
      };
      row.appendChild(dctBtn);
      listEl.appendChild(row);
    });
    modal.classList.remove('hidden');
  });
  document.getElementById('nearest-close')?.addEventListener('click', () => {
    document.getElementById('nearest-modal')?.classList.add('hidden');
  });

  // Recherche terrain / Direct-To
  document.getElementById('btn-search-go')?.addEventListener('click', () => {
    const q = document.getElementById('search-input')?.value;
    const found = AiracManager.findByCode(q);
    if (!found) {
      alert('Aérodrome ou balise introuvable dans la base locale.');
      return;
    }
    FlightPlanManager.setDirectTo({
      name: found.code,
      lat: found.lat,
      lon: found.lon,
      freq: found.freq || ''
    });
    if (OaciMapState.map) {
      OaciMapState.followGps = false;
      OaciMapState.map.easeTo({ center: geoToMap(found.lat, found.lon), duration: 400 });
    }
  });

  // Popup actions
  document.getElementById('popup-close')?.addEventListener('click', () => {
    document.getElementById('feature-popup')?.classList.add('hidden');
  });
  document.getElementById('popup-dct')?.addEventListener('click', () => {
    const f = OaciMapState.selectedFeature;
    if (!f) return;
    FlightPlanManager.setDirectTo({ name: f.icao || f.name, lat: f.lat, lon: f.lon, freq: f.freq || '' });
    document.getElementById('feature-popup')?.classList.add('hidden');
  });
  document.getElementById('popup-add-wp')?.addEventListener('click', () => {
    const f = OaciMapState.selectedFeature;
    if (!f) return;
    FlightPlanManager.addWaypoint({ name: f.icao || f.name, lat: f.lat, lon: f.lon, freq: f.freq || '' });
    document.getElementById('feature-popup')?.classList.add('hidden');
  });
  document.getElementById('popup-load-vac')?.addEventListener('click', () => {
    const f = OaciMapState.selectedFeature;
    document.getElementById('feature-popup')?.classList.add('hidden');
    if (window.VfrNative && typeof window.VfrNative.pickVacPdf === 'function') {
      window.VfrNative.pickVacPdf(f?.icao || 'active_vac');
    } else {
      alert("L'import PDF natif s'exécute dans l'application Android APK.");
    }
  });

  // Export GPX
  document.getElementById('btn-export-gpx')?.addEventListener('click', exportTraceAsGpx);

  // Import PDF OACI & Calibration réticule
  document.getElementById('btn-import-oaci-pdf')?.addEventListener('click', () => {
    if (window.VfrNative && typeof window.VfrNative.pickOaciPdf === 'function') {
      window.VfrNative.pickOaciPdf();
    } else {
      alert("L'import PDF OACI s'exécute dans l'application Android APK.");
    }
  });

  document.getElementById('btn-oaci-calib-mode')?.addEventListener('click', () => {
    OaciMapState.oaciCalibActive = !OaciMapState.oaciCalibActive;
    document.getElementById('reticle')?.classList.toggle('hidden', !OaciMapState.oaciCalibActive);
    document.getElementById('oaci-calib-panel')?.classList.toggle('hidden', !OaciMapState.oaciCalibActive);
  });

  document.getElementById('btn-oaci-add-gcp')?.addEventListener('click', () => {
    if (!OaciMapState.map) return;
    const code = document.getElementById('oaci-gcp-code')?.value;
    const found = AiracManager.findByCode(code);
    if (!found) {
      alert('Saisis un code OACI ou VOR valide (ex: LFJI, LFBH, LFBI, POI).');
      return;
    }
    const center = OaciMapState.map.getCenter();
    const [xPdf, yPdf] = mapLngLatToPdf(center.lng, center.lat);
    OaciMapState.gcps.push({ lat: found.lat, lon: found.lon, xPdf, yPdf, label: found.code });

    const statusEl = document.getElementById('oaci-calib-status');
    if (OaciMapState.gcps.length >= 2) {
      const solved = solveConformalCalibration(OaciMapState.gcps);
      if (solved) {
        OaciMapState.calibration = solved;
        saveOaciCalibration();
        OaciMapState.map.getSource('airfields-src')?.setData(buildAirfieldsGeoJson());
        OaciMapState.map.getSource('graticule')?.setData(buildGraticuleGeoJson());
        window.refreshRouteOnMap();
        window.refreshVacOverlayOnMap();
        if (statusEl) {
          statusEl.textContent = `Calibration validée (${solved.pointCount} pts · écart RMS : ${Math.round(solved.rmsMeters)} m).`;
        }
      }
    } else if (statusEl) {
      statusEl.textContent = `1er point (${found.code}) enregistré. Place le réticule sur un 2e repère éloigné.`;
    }
  });

  document.getElementById('btn-oaci-clear-gcp')?.addEventListener('click', () => {
    OaciMapState.gcps = [];
    saveOaciCalibration();
    const statusEl = document.getElementById('oaci-calib-status');
    if (statusEl) statusEl.textContent = 'Points effacés. Aligne le réticule (+) sur un aérodrome connu.';
  });

  // Import & Contrôles VAC
  document.getElementById('btn-import-vac-pdf')?.addEventListener('click', () => {
    if (window.VfrNative && typeof window.VfrNative.pickVacPdf === 'function') {
      window.VfrNative.pickVacPdf('active_vac');
    } else {
      alert("L'import PDF VAC s'exécute dans l'application Android APK.");
    }
  });

  document.getElementById('btn-open-vac-calibrator')?.addEventListener('click', () => {
    VacManager.openCalibratorModal();
  });
  document.getElementById('btn-vac-calib-open')?.addEventListener('click', () => {
    VacManager.openCalibratorModal();
  });
  document.getElementById('vac-calib-close')?.addEventListener('click', () => {
    document.getElementById('vac-calib-modal')?.classList.add('hidden');
  });

  document.getElementById('vac-page-select')?.addEventListener('change', (e) => {
    VacManager.activePage = parseInt(e.target.value, 10) || 0;
    VacManager.persist();
    VacManager.updateStatusLine();
    window.refreshVacOverlayOnMap();
  });

  document.getElementById('vac-opacity')?.addEventListener('input', (e) => {
    VacManager.opacity = Math.max(0, Math.min(1, parseInt(e.target.value, 10) / 100));
    window.refreshVacOverlayOnMap();
  });

  document.getElementById('btn-toggle-vac')?.addEventListener('click', (e) => {
    VacManager.visible = !VacManager.visible;
    e.target.textContent = VacManager.visible ? 'ON' : 'OFF';
    window.refreshVacOverlayOnMap();
  });

  document.getElementById('btn-vac-auto-retry')?.addEventListener('click', () => {
    VacManager.analyzeAndAutoCalibrate({
      vacId: VacManager.vacId,
      pageCount: VacManager.pageCount,
      pagesMeta: VacManager.pagesMeta
    });
  });

  document.getElementById('btn-vac-2pt-start')?.addEventListener('click', () => {
    VacManager.startManualTwoPoint();
  });

  document.getElementById('vac-preview-canvas')?.addEventListener('click', (e) => {
    const canvas = e.currentTarget;
    const rect = canvas.getBoundingClientRect();
    const u = (e.clientX - rect.left) / rect.width;
    const v = (e.clientY - rect.top) / rect.height;
    VacManager.handlePreviewTap(u, v);
  });

  document.getElementById('btn-vac-2pt-confirm-map')?.addEventListener('click', () => {
    if (!OaciMapState.map) return;
    const c = OaciMapState.map.getCenter();
    const [lat, lon] = mapToGeo(c.lng, c.lat);
    VacManager.confirmMapReticleForManualStep(lat, lon);
  });

  document.getElementById('btn-vac-2pt-reset')?.addEventListener('click', () => {
    VacManager.startManualTwoPoint();
  });

  // Synchronisation AIRAC
  document.getElementById('btn-check-airac')?.addEventListener('click', () => {
    AiracManager.syncFromManifestUrl(null);
  });

  // Import hybride PC (.json)
  document.getElementById('file-hybrid')?.addEventListener('change', async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    try {
      const txt = await file.text();
      FlightPlanManager.importPcJson(txt);
    } catch (err) {
      alert(`Erreur lors de l'import JSON : ${err.message}`);
    }
  });
});
