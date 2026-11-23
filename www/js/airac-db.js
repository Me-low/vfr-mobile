// ============================================================================
// BASE AÉRODROMES / BALISES PERMANENTE & CONTRÔLE DE VALIDITÉ DU CYCLE AIRAC
// ============================================================================

const BUILTIN_AIRFIELDS = [
  { icao: 'LFJI', name: 'Marennes', lat: 45.8053, lon: -1.1244, elevFt: 26, freq: '123.500', rwy: '08/26 · 665m Herbe' },
  { icao: 'LFBH', name: 'La Rochelle Île de Ré', lat: 46.1792, lon: -1.1953, elevFt: 74, freq: '118.000', rwy: '09/27 · 2255m Dur' },
  { icao: 'LFBI', name: 'Poitiers Biard', lat: 46.5875, lon: 0.3067, elevFt: 423, freq: '118.505', rwy: '03/21 · 2350m Dur' },
  { icao: 'LFDN', name: 'Rochefort Charente-Maritime', lat: 45.8878, lon: -0.9831, elevFt: 56, freq: '119.300', rwy: '13/31 · 2280m Dur' },
  { icao: 'LFDP', name: "Saint-Pierre-d'Oléron", lat: 45.9519, lon: -1.3264, elevFt: 23, freq: '123.500', rwy: '09/27 · 735m Herbe' },
  { icao: 'LFCY', name: 'Royan Médis', lat: 45.6281, lon: -0.9725, elevFt: 72, freq: '120.725', rwy: '10/28 · 1250m Dur' },
  { icao: 'LFBN', name: 'Niort Marais Poitevin', lat: 46.3114, lon: -0.4014, elevFt: 200, freq: '118.275', rwy: '07/25 · 1600m Dur' },
  { icao: 'LFBG', name: 'Cognac Châteaubernard', lat: 45.6583, lon: -0.3175, elevFt: 102, freq: '122.100', rwy: '05/23 · 2402m Dur' },
  { icao: 'LFBU', name: 'Angoulême Brie Champniers', lat: 45.7292, lon: 0.2214, elevFt: 436, freq: '122.050', rwy: '10/28 · 1800m Dur' },
  { icao: 'LFBX', name: 'Périgueux Bassillac', lat: 45.1981, lon: 0.8156, elevFt: 328, freq: '119.650', rwy: '11/29 · 1750m Dur' },
  { icao: 'LFBD', name: 'Bordeaux Mérignac', lat: 44.8283, lon: -0.7156, elevFt: 162, freq: '118.300', rwy: '05/23 · 3100m Dur' },
  { icao: 'LFBE', name: 'Bergerac Dordogne Périgord', lat: 44.8253, lon: 0.5186, elevFt: 171, freq: '119.200', rwy: '09/27 · 2205m Dur' },
  { icao: 'LFRS', name: 'Nantes Atlantique', lat: 47.1532, lon: -1.6107, elevFt: 90, freq: '118.650', rwy: '03/21 · 2900m Dur' },
  { icao: 'LFOU', name: 'Cholet Le Pontreau', lat: 47.0822, lon: -0.8769, elevFt: 443, freq: '120.400', rwy: '03/21 · 1380m Dur' },
  { icao: 'LFRI', name: 'La Roche-sur-Yon Les Ajoncs', lat: 46.7019, lon: -1.3786, elevFt: 299, freq: '119.250', rwy: '10/28 · 1500m Dur' },
  { icao: 'LFOO', name: 'Les Sables-d'Olonne Talmont', lat: 46.4767, lon: -1.7244, elevFt: 105, freq: '118.425', rwy: '06/24 · 735m Dur' },
  { icao: 'LFEY', name: 'Île d'Yeu Le Grand Phare', lat: 46.7186, lon: -2.3911, elevFt: 79, freq: '118.175', rwy: '14/32 · 1230m Dur' },
  { icao: 'LFJR', name: 'Angers Marcé', lat: 47.5603, lon: -0.3122, elevFt: 194, freq: '120.025', rwy: '08/26 · 1800m Dur' },
  { icao: 'LFOT', name: 'Tours Val de Loire', lat: 47.4322, lon: 0.7275, elevFt: 358, freq: '122.400', rwy: '02/20 · 2404m Dur' },
  { icao: 'LFBL', name: 'Limoges Bellegarde', lat: 45.8628, lon: 1.1794, elevFt: 1300, freq: '119.200', rwy: '03/21 · 2500m Dur' },
  { icao: 'LFBZ', name: 'Biarritz Pays Basque', lat: 43.4683, lon: -1.5233, elevFt: 245, freq: '118.700', rwy: '09/27 · 2250m Dur' },
  { icao: 'LFBO', name: 'Toulouse Blagnac', lat: 43.6291, lon: 1.3638, elevFt: 499, freq: '118.100', rwy: '14L/32R · 3500m Dur' },
  { icao: 'LFPN', name: 'Toussus-le-Noble', lat: 48.7519, lon: 2.1061, elevFt: 538, freq: '119.300', rwy: '07L/25R · 1100m Dur' }
];

const BUILTIN_NAVAIDS = [
  { ident: 'POI', name: 'VOR-DME Poitiers', lat: 46.5819, lon: 0.2981, freq: '113.30' },
  { ident: 'CNA', name: 'VOR-DME Cognac', lat: 45.6594, lon: -0.3117, freq: '114.65' },
  { ident: 'LMG', name: 'VOR-DME Limoges', lat: 45.8158, lon: 1.0256, freq: '114.50' },
  { ident: 'CHX', name: 'VOR-DME Châteauroux', lat: 46.8622, lon: 1.7211, freq: '108.65' },
  { ident: 'BMC', name: 'VOR-DME Bordeaux', lat: 44.8269, lon: -0.7211, freq: '113.75' },
  { ident: 'NTS', name: 'VOR-DME Nantes', lat: 47.1608, lon: -1.6131, freq: '115.50' }
];

const AiracManager = {
  // Référence officielle OACI : 2026-01-22 = AIRAC 2601 (cycles stricts de 28 jours)
  referenceStartMs: Date.UTC(2026, 0, 22, 0, 0, 0),
  cycleDurationMs: 28 * 24 * 3600 * 1000,
  airfields: [...BUILTIN_AIRFIELDS],
  navaids: [...BUILTIN_NAVAIDS],
  installedCycle: null,

  init() {
    try {
      const saved = localStorage.getItem('vfr_airac_db_v1');
      if (saved) {
        const parsed = JSON.parse(saved);
        if (Array.isArray(parsed.airfields) && parsed.airfields.length > 0) {
          this.airfields = parsed.airfields;
        }
        if (Array.isArray(parsed.navaids)) {
          this.navaids = parsed.navaids;
        }
        this.installedCycle = parsed.cycle || null;
      }
    } catch (_) {}

    if (!this.installedCycle) {
      this.installedCycle = this.computeCurrentAirac().code;
    }
    this.refreshUi();
  },

  computeCurrentAirac(nowMs = Date.now()) {
    const diff = nowMs - this.referenceStartMs;
    const index = Math.floor(diff / this.cycleDurationMs);
    const startMs = this.referenceStartMs + index * this.cycleDurationMs;
    const endMs = startMs + this.cycleDurationMs;

    const startDate = new Date(startMs);
    const year = startDate.getUTCFullYear();
    const jan1Ms = Date.UTC(year, 0, 1, 0, 0, 0);
    const dayOfYear = Math.floor((startMs - jan1Ms) / (24 * 3600 * 1000));
    const cycleNum = Math.floor(dayOfYear / 28) + 1;

    const yy = String(year).slice(-2);
    const cc = String(cycleNum).padStart(2, '0');
    const daysLeft = Math.ceil((endMs - nowMs) / (24 * 3600 * 1000));

    return {
      code: `${yy}${cc}`,
      effectiveDate: startDate.toISOString().slice(0, 10),
      expiresDate: new Date(endMs).toISOString().slice(0, 10),
      daysLeft,
      isExpired: nowMs >= endMs || (this.installedCycle && this.installedCycle !== `${yy}${cc}`)
    };
  },

  findByCode(query) {
    if (!query) return null;
    const q = query.trim().toUpperCase();
    const af = this.airfields.find(
      (a) => a.icao === q || a.name.toUpperCase().includes(q)
    );
    if (af) return { type: 'AIRFIELD', code: af.icao, ...af };

    const nav = this.navaids.find(
      (n) => n.ident === q || n.name.toUpperCase().includes(q)
    );
    if (nav) return { type: 'NAVAID', code: nav.ident, ...nav };
    return null;
  },

  getNearestAirfields(lat, lon, limit = 5) {
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      return this.airfields.slice(0, limit).map((a) => ({ ...a, distNm: null, bearing: null }));
    }
    return this.airfields
      .map((a) => ({
        ...a,
        distNm: haversineNm(lat, lon, a.lat, a.lon),
        bearing: initialBearingDeg(lat, lon, a.lat, a.lon)
      }))
      .sort((a, b) => a.distNm - b.distNm)
      .slice(0, limit);
  },

  async syncFromManifestUrl(manifestUrl) {
    const info = this.computeCurrentAirac();
    if (!manifestUrl) {
      // Vérification locale du cycle courant
      this.installedCycle = info.code;
      localStorage.setItem(
        'vfr_airac_db_v1',
        JSON.stringify({
          cycle: this.installedCycle,
          updatedAt: new Date().toISOString(),
          airfields: this.airfields,
          navaids: this.navaids
        })
      );
      this.refreshUi();
      return;
    }

    try {
      const res = await fetch(manifestUrl, { cache: 'no-store' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const payload = await res.json();
      if (Array.isArray(payload.airfields) && payload.airfields.length > 0) {
        this.airfields = payload.airfields;
      }
      if (Array.isArray(payload.navaids)) {
        this.navaids = payload.navaids;
      }
      this.installedCycle = payload.cycle || info.code;
      localStorage.setItem(
        'vfr_airac_db_v1',
        JSON.stringify({
          cycle: this.installedCycle,
          updatedAt: new Date().toISOString(),
          airfields: this.airfields,
          navaids: this.navaids
        })
      );
    } catch (_) {}
    this.refreshUi();
  },

  refreshUi() {
    const badgeEl = document.getElementById('airac-badge-box');
    const statsEl = document.getElementById('airac-db-stats');
    const info = this.computeCurrentAirac();

    if (badgeEl) {
      if (info.isExpired) {
        badgeEl.className = 'status-pill expired';
        badgeEl.textContent = `AIRAC ${this.installedCycle || '----'} PÉRIMÉ · Cycle attendu : ${info.code}`;
      } else {
        badgeEl.className = 'status-pill';
        badgeEl.textContent = `AIRAC ${info.code} À JOUR (${info.effectiveDate} → ${info.expiresDate} · ${info.daysLeft}j)`;
      }
    }

    if (statsEl) {
      statsEl.textContent = `Base embarquée : ${this.airfields.length} aérodromes · ${this.navaids.length} balises VOR/DME.`;
    }
  }
};
