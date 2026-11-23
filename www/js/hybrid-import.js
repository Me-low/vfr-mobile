// ============================================================================
// IMPORT HYBRIDE DEPUIS L'OUTIL PC & GESTION AUTONOME DU PLAN DE VOL
// ============================================================================

const FlightPlanManager = {
  title: 'Navigation VFR',
  waypoints: [], // [{ name, lat, lon, altFt, freq }]
  activeLegIndex: 0,
  notams: [],
  airspacesGeoJson: { type: 'FeatureCollection', features: [] },

  init() {
    try {
      const saved = localStorage.getItem('vfr_flight_plan_v1');
      if (saved) {
        const data = JSON.parse(saved);
        this.title = data.title || 'Navigation VFR';
        this.waypoints = Array.isArray(data.waypoints) ? data.waypoints : [];
        this.activeLegIndex = Number.isInteger(data.activeLegIndex) ? data.activeLegIndex : 0;
        this.notams = Array.isArray(data.notams) ? data.notams : [];
        if (data.airspacesGeoJson) this.airspacesGeoJson = data.airspacesGeoJson;
      }
    } catch (_) {}
    this.renderUi();
  },

  persist() {
    try {
      localStorage.setItem(
        'vfr_flight_plan_v1',
        JSON.stringify({
          title: this.title,
          waypoints: this.waypoints,
          activeLegIndex: this.activeLegIndex,
          notams: this.notams,
          airspacesGeoJson: this.airspacesGeoJson
        })
      );
    } catch (_) {}
  },

  setDirectTo(wp) {
    this.title = `DCT → ${wp.name}`;
    this.waypoints = [wp];
    this.activeLegIndex = 0;
    this.persist();
    this.renderUi();
    if (window.refreshRouteOnMap) window.refreshRouteOnMap();
  },

  addWaypoint(wp) {
    this.waypoints.push(wp);
    this.persist();
    this.renderUi();
    if (window.refreshRouteOnMap) window.refreshRouteOnMap();
  },

  removeWaypoint(idx) {
    this.waypoints.splice(idx, 1);
    if (this.activeLegIndex >= this.waypoints.length) {
      this.activeLegIndex = Math.max(0, this.waypoints.length - 1);
    }
    this.persist();
    this.renderUi();
    if (window.refreshRouteOnMap) window.refreshRouteOnMap();
  },

  updateNavigationProgress() {
    const legEl = document.getElementById('active-leg-banner');
    const distHudEl = document.getElementById('hud-dist-next');

    if (this.waypoints.length === 0) {
      if (legEl) legEl.textContent = 'Aucune branche active (recherche un terrain ou importe un vol PC)';
      if (distHudEl) distHudEl.textContent = 'WP -- NM';
      return;
    }

    const target = this.waypoints[Math.min(this.activeLegIndex, this.waypoints.length - 1)];
    if (!Number.isFinite(SensorState.lat) || !Number.isFinite(SensorState.lon)) {
      if (legEl) legEl.textContent = `Vers ${target.name} (${target.freq || 'SIV'}) · Attente GPS`;
      return;
    }

    const distNm = haversineNm(SensorState.lat, SensorState.lon, target.lat, target.lon);
    const brg = initialBearingDeg(SensorState.lat, SensorState.lon, target.lat, target.lon);

    // Passage automatique au waypoint suivant à moins de 1.0 NM
    if (distNm < 1.0 && this.activeLegIndex < this.waypoints.length - 1) {
      this.activeLegIndex += 1;
      this.persist();
      this.renderUi();
      return;
    }

    let eteStr = '-- min';
    if (Number.isFinite(SensorState.groundSpeedKt) && SensorState.groundSpeedKt > 10) {
      const mins = Math.round((distNm / SensorState.groundSpeedKt) * 60);
      eteStr = `${mins} min`;
    }

    if (distHudEl) {
      distHudEl.textContent = `${target.name} · ${distNm.toFixed(1)} NM`;
    }
    if (legEl) {
      legEl.textContent = `Branche → ${target.name} : ${String(Math.round(brg)).padStart(3, '0')}° · ${distNm.toFixed(1)} NM · ETE ${eteStr}`;
    }
  },

  importPcJson(rawText) {
    const data = JSON.parse(rawText);
    if (!data || typeof data !== 'object') throw new Error('JSON invalide');

    this.title = data.title || data.name || 'Vol importé PC';
    const rawWps = Array.isArray(data.waypoints)
      ? data.waypoints
      : Array.isArray(data.route)
      ? data.route
      : [];

    this.waypoints = rawWps
      .filter((w) => Number.isFinite(w.lat) && Number.isFinite(w.lon))
      .map((w) => ({
        name: w.name || w.icao || w.ident || 'WPT',
        lat: Number(w.lat),
        lon: Number(w.lon),
        altFt: Number.isFinite(w.altFt) ? Number(w.altFt) : null,
        freq: w.freq || ''
      }));

    this.activeLegIndex = 0;
    this.notams = Array.isArray(data.notams) ? data.notams : [];

    if (data.airspaces && data.airspaces.type === 'FeatureCollection') {
      this.airspacesGeoJson = data.airspaces;
    } else if (Array.isArray(data.zones)) {
      this.airspacesGeoJson = {
        type: 'FeatureCollection',
        features: data.zones
          .filter((z) => Array.isArray(z.polygon) && z.polygon.length >= 3)
          .map((z) => ({
            type: 'Feature',
            properties: {
              name: z.name || 'Zone',
              summary: z.summary || z.text || '',
              floor: z.floor || 'SFC',
              ceiling: z.ceiling || '',
              color: z.color || '#ef4444'
            },
            geometry: {
              type: 'Polygon',
              coordinates: [z.polygon]
            }
          }))
      };
    }

    if (Number.isFinite(data.qnh)) {
      SensorState.qnhHpa = Math.round(data.qnh);
      recomputeBaroAltitudes();
      updateHudTelemetry();
    }

    this.persist();
    this.renderUi();
    if (window.refreshRouteOnMap) window.refreshRouteOnMap();
  },

  renderUi() {
    const wpListEl = document.getElementById('waypoint-list');
    if (wpListEl) {
      wpListEl.innerHTML = '';
      this.waypoints.forEach((wp, idx) => {
        const row = document.createElement('div');
        row.className = 'list-item';
        const activeMark = idx === this.activeLegIndex ? '▶ ' : '';
        row.innerHTML = `<span><strong>${activeMark}${wp.name}</strong> ${wp.freq ? '· ' + wp.freq : ''}</span>`;
        const delBtn = document.createElement('button');
        delBtn.className = 'btn-sm';
        delBtn.textContent = '✕';
        delBtn.onclick = () => this.removeWaypoint(idx);
        row.appendChild(delBtn);
        wpListEl.appendChild(row);
      });
    }

    const summaryEl = document.getElementById('hybrid-summary');
    const notamListEl = document.getElementById('hybrid-notam-list');
    if (summaryEl && this.waypoints.length > 0) {
      summaryEl.textContent = `${this.title} · ${this.waypoints.length} points · ${this.notams.length} NOTAM importés.`;
    }
    if (notamListEl) {
      notamListEl.innerHTML = '';
      this.notams.slice(0, 20).forEach((n) => {
        const div = document.createElement('div');
        div.className = 'list-item';
        const title = typeof n === 'string' ? n : n.id || n.title || 'NOTAM';
        const summary = typeof n === 'string' ? '' : n.summary || n.text || '';
        div.innerHTML = `<div><strong>${title}</strong><div class="muted-text">${summary}</div></div>`;
        notamListEl.appendChild(div);
      });
    }

    this.updateNavigationProgress();
  }
};
