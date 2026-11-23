// ============================================================================
// CALAGE AUTOMATIQUE DES PLANCHES VAC (TXT + GRATICULE) & SECOURS 2 POINTS
// ============================================================================

const VacManager = {
  vacId: 'active_vac',
  icao: null,
  pageCount: 0,
  pagesMeta: [],
  activePage: 0,
  visible: false,
  opacity: 0.70,
  calibrationsByPage: {}, // { [pageIdx]: { topLeft, topRight, bottomRight, bottomLeft, confidence, method } }
  manualStep: 0, // 0 = idle, 1 = waiting UV1 on preview, 2 = waiting OACI reticle for Pt1, 3 = waiting UV2, 4 = waiting OACI reticle for Pt2
  manualPt1: null,
  manualPt2: null,

  init() {
    try {
      const saved = localStorage.getItem('vfr_vac_state_v1');
      if (saved) {
        const parsed = JSON.parse(saved);
        this.icao = parsed.icao || null;
        this.pageCount = parsed.pageCount || 0;
        this.pagesMeta = parsed.pagesMeta || [];
        this.activePage = parsed.activePage || 0;
        this.calibrationsByPage = parsed.calibrationsByPage || {};
      }
    } catch (_) {}

    if (this.pageCount > 0) {
      this.populatePageSelector();
      const ctrl = document.getElementById('vac-overlay-controls');
      if (ctrl) ctrl.classList.remove('hidden');
      this.updateStatusLine();
    }
  },

  persist() {
    try {
      localStorage.setItem(
        'vfr_vac_state_v1',
        JSON.stringify({
          icao: this.icao,
          pageCount: this.pageCount,
          pagesMeta: this.pagesMeta,
          activePage: this.activePage,
          calibrationsByPage: this.calibrationsByPage
        })
      );
    } catch (_) {}
  },

  /** Parse une coordonnée DMS SIA, y compris avec coquilles typographiques courantes */
  parseSiaDms(rawStr, isLat) {
    const cleaned = rawStr
      .replace(/[°'’"″]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .toUpperCase();
    const hemiMatch = cleaned.match(/[NSEW]$/);
    if (!hemiMatch) return null;
    const hemi = hemiMatch[0];
    const nums = cleaned
      .slice(0, -1)
      .trim()
      .split(' ')
      .filter(Boolean);

    let deg = 0, min = 0, sec = 0;
    if (nums.length >= 3) {
      deg = parseFloat(nums[0]);
      min = parseFloat(nums[1]);
      sec = parseFloat(nums[2]);
    } else if (nums.length === 2) {
      deg = parseFloat(nums[0]);
      // Gestion des coquilles SIA type "332" -> 33' 20" ou "42" -> 42' 00"
      if (nums[1].length === 3) {
        min = parseFloat(nums[1].slice(0, 2));
        sec = parseFloat(nums[1].slice(2)) * 10;
      } else {
        min = parseFloat(nums[1]);
      }
    } else {
      return null;
    }

    if (!Number.isFinite(deg) || !Number.isFinite(min) || !Number.isFinite(sec)) return null;
    if (min >= 60 || sec >= 60) return null;
    if (isLat && (deg > 90 || (hemi !== 'N' && hemi !== 'S'))) return null;
    if (!isLat && (deg > 180 || (hemi !== 'E' && hemi !== 'W'))) return null;

    let val = deg + min / 60 + sec / 3600;
    if (hemi === 'S' || hemi === 'W') val = -val;
    return val;
  },

  /** Extrait automatiquement texte, ARP, points de report et graticule via PDF.js */
  async analyzeAndAutoCalibrate(meta) {
    this.vacId = meta.vacId || 'active_vac';
    this.pageCount = meta.pageCount || 0;
    this.pagesMeta = meta.pagesMeta || [];
    this.calibrationsByPage = {};

    const statusEl = document.getElementById('vac-calib-status');
    if (statusEl) statusEl.textContent = 'Extraction automatique des coordonnées & repères SIA...';

    if (!window.pdfjsLib) {
      if (statusEl) statusEl.textContent = 'PDF.js indisponible : utilise le calage manuel 2 points.';
      return;
    }

    window.pdfjsLib.GlobalWorkerOptions.workerSrc = 'vendor/pdf.worker.min.js';

    try {
      const pdfUrl = `https://appassets.androidplatform.net/vac/${this.vacId}/raw.pdf?t=${Date.now()}`;
      const loadingTask = window.pdfjsLib.getDocument(pdfUrl);
      const pdfDoc = await loadingTask.promise;

      let detectedIcao = null;
      let arpLat = null;
      let arpLon = null;
      const reportingPoints = {}; // { 'N': { lat, lon }, 'WA': { lat, lon }, ... }
      const pageTextItems = {};

      for (let p = 1; p <= pdfDoc.numPages; p++) {
        const page = await pdfDoc.getPage(p);
        const viewport = page.getViewport({ scale: 1.0 });
        const tc = await page.getTextContent();

        const items = tc.items.map((it) => {
          const tx = it.transform[4];
          const ty = it.transform[5];
          // Coordonnées normalisées (0,0 en haut à gauche, 1,1 en bas à droite)
          const u = tx / viewport.width;
          const v = 1 - ty / viewport.height;
          return { str: (it.str || '').trim(), u, v };
        }).filter((it) => it.str.length > 0);

        pageTextItems[p - 1] = items;
        const fullText = items.map((i) => i.str).join(' ');

        if (!detectedIcao) {
          const icaoMatch = fullText.match(/\bAD\s*2\s*(LF[A-Z]{2})\b/);
          if (icaoMatch) detectedIcao = icaoMatch[1];
        }

        if (arpLat === null) {
          const latMatch = fullText.match(/LAT\s*:\s*(\d{2}\s+\d{2}\s+\d{2}\s*[NS])/);
          const lonMatch = fullText.match(/LONG\s*:\s*(\d{3}\s+\d{2}\s+\d{2}\s*[EW])/);
          if (latMatch && lonMatch) {
            arpLat = this.parseSiaDms(latMatch[1], true);
            arpLon = this.parseSiaDms(lonMatch[1], false);
          }
        }

        // Extraction des points de compte rendu depuis la table TXT 01
        const rpRegex = /\b(N|S|E|W|NE|NW|SE|SW|EA|WA|NA|SA)(?:\s*\(\d+\))?\s+(\d{2}°\d{2,3}[\d'’"″\s]*[NS])\s*[-–]\s*(\d{3}°\d{2,3}[\d'’"″\s]*[EW])/g;
        let m;
        while ((m = rpRegex.exec(fullText)) !== null) {
          const code = m[1];
          const lat = this.parseSiaDms(m[2], true);
          const lon = this.parseSiaDms(m[3], false);
          if (lat !== null && lon !== null) {
            reportingPoints[code] = { lat, lon };
          }
        }
      }

      if (detectedIcao) {
        this.icao = detectedIcao;
        const af = AiracManager.findByCode(detectedIcao);
        if (af && (arpLat === null || arpLon === null)) {
          arpLat = af.lat;
          arpLon = af.lon;
        }
      }

      // Calage automatique de chaque planche cartographique (APP / ATT)
      for (let pageIdx = 0; pageIdx < pdfDoc.numPages; pageIdx++) {
        const items = pageTextItems[pageIdx] || [];
        const pageStr = items.map((i) => i.str).join(' ');
        const isChartPage =
          /APP\s*01|ATT\s*01|APPROCHE A VUE|ATTERRISSAGE A VUE/.test(pageStr);
        if (!isChartPage) continue;

        const anchors = [];

        // 1) Appariement des badges de points de report (N, W, WA, EA, E, S) sur la carte
        for (const [code, coords] of Object.entries(reportingPoints)) {
          if (arpLat !== null && haversineNm(arpLat, arpLon, coords.lat, coords.lon) > 25) {
            continue; // Ignore les coquilles trop éloignées
          }
          const badge = items.find(
            (it) => it.str === code && it.u > 0.05 && it.u < 0.95 && it.v > 0.08 && it.v < 0.78
          );
          if (badge) {
            anchors.push({ u: badge.u, v: badge.v, lat: coords.lat, lon: coords.lon, label: code });
          }
        }

        // 2) Appariement du label terrain / ARP
        if (arpLat !== null && arpLon !== null && this.icao) {
          const centerBadge = items.find(
            (it) =>
              (it.str === this.icao || it.str === 'POITIERS' || it.str === 'Biard') &&
              it.u > 0.2 && it.u < 0.8 && it.v > 0.2 && it.v < 0.7
          );
          if (centerBadge) {
            anchors.push({ u: centerBadge.u, v: centerBadge.v, lat: arpLat, lon: arpLon, label: 'ARP' });
          }
        }

        // 3) Extraction des graduations de graticule latitude / longitude
        const baseLatDeg = arpLat !== null ? Math.floor(arpLat) : null;
        const baseLonDeg = arpLon !== null ? Math.round(arpLon) : null;
        const latTicks = [];
        const lonTicks = [];

        for (const it of items) {
          const degMin = it.str.match(/^(\d{2,3})°\s*(\d{2})['’]?$/);
          if (degMin) {
            const d = parseInt(degMin[1], 10);
            const mn = parseInt(degMin[2], 10);
            if (d >= 41 && d <= 52 && (it.u < 0.12 || it.u > 0.88)) {
              latTicks.push({ v: it.v, lat: d + mn / 60 });
            } else if (d <= 10 && it.v > 0.55) {
              const sign = arpLon !== null && arpLon < -0.5 ? -1 : 1;
              lonTicks.push({ u: it.u, lon: sign * (d + mn / 60) });
            }
          } else if (baseLatDeg !== null && /^(\d{2})['’]$/.test(it.str) && (it.u < 0.12 || it.u > 0.88)) {
            const mn = parseInt(it.str, 10);
            latTicks.push({ v: it.v, lat: baseLatDeg + mn / 60 });
          } else if (baseLonDeg !== null && /^(\d{2})['’]$/.test(it.str) && it.v > 0.55) {
            const mn = parseInt(it.str, 10);
            const sign = arpLon < 0 ? -1 : 1;
            lonTicks.push({ u: it.u, lon: sign * (Math.abs(baseLonDeg) + mn / 60) });
          }
        }

        if (latTicks.length >= 2 && lonTicks.length >= 2) {
          latTicks.sort((a, b) => a.v - b.v);
          lonTicks.sort((a, b) => a.u - b.u);
          const lt1 = latTicks[0], lt2 = latTicks[latTicks.length - 1];
          const ln1 = lonTicks[0], ln2 = lonTicks[lonTicks.length - 1];
          anchors.push({ u: ln1.u, v: lt1.v, lat: lt1.lat, lon: ln1.lon, label: 'GRAT_NW' });
          anchors.push({ u: ln2.u, v: lt2.v, lat: lt2.lat, lon: ln2.lon, label: 'GRAT_SE' });
        }

        if (anchors.length >= 2) {
          // Utilise les 2 ancres les plus éloignées pour maximiser le bras de levier géométrique
          let bestPair = [anchors[0], anchors[1]];
          let maxDist = 0;
          for (let i = 0; i < anchors.length; i++) {
            for (let j = i + 1; j < anchors.length; j++) {
              const d = Math.hypot(anchors[j].u - anchors[i].u, anchors[j].v - anchors[i].v);
              if (d > maxDist) {
                maxDist = d;
                bestPair = [anchors[i], anchors[j]];
              }
            }
          }

          const corners = computeVacCornersFromTwoPoints(bestPair[0], bestPair[1]);
          if (corners) {
            this.calibrationsByPage[pageIdx] = {
              ...corners,
              method: `AUTO (${bestPair[0].label} + ${bestPair[1].label})`,
              confidence: anchors.length >= 3 ? 92 : 78
            };
          }
        }
      }

      const calibratedPages = Object.keys(this.calibrationsByPage).map(Number);
      if (calibratedPages.length > 0) {
        this.activePage = calibratedPages[0];
        this.visible = true;
      }

      this.persist();
      this.populatePageSelector();
      this.updateStatusLine();
      if (window.refreshVacOverlayOnMap) window.refreshVacOverlayOnMap();
    } catch (err) {
      console.error(err);
      if (statusEl) statusEl.textContent = `Calage auto incomplet (${err.message}). Utilise le calage manuel 2 points.`;
    }
  },

  populatePageSelector() {
    const sel = document.getElementById('vac-page-select');
    const ctrl = document.getElementById('vac-overlay-controls');
    const toggleBtn = document.getElementById('btn-toggle-vac');
    if (!sel) return;

    sel.innerHTML = '';
    for (let i = 0; i < this.pageCount; i++) {
      const opt = document.createElement('option');
      opt.value = String(i);
      const hasCal = !!this.calibrationsByPage[i];
      opt.textContent = `Page ${i + 1}${hasCal ? ' ✓' : ''}`;
      if (i === this.activePage) opt.selected = true;
      sel.appendChild(opt);
    }

    if (ctrl && this.pageCount > 0) ctrl.classList.remove('hidden');
    if (toggleBtn) toggleBtn.textContent = this.visible ? 'ON' : 'OFF';
  },

  updateStatusLine() {
    const lineEl = document.getElementById('vac-status-line');
    const modalStatusEl = document.getElementById('vac-calib-status');
    const cal = this.calibrationsByPage[this.activePage];

    let msg;
    if (this.pageCount === 0) {
      msg = 'Aucune planche VAC chargée.';
    } else if (cal) {
      msg = `${this.icao || 'VAC'} · Page ${this.activePage + 1}/${this.pageCount} calée [${cal.method}] · Rot ${cal.rotationDeg.toFixed(1)}°`;
    } else {
      msg = `${this.icao || 'VAC'} · Page ${this.activePage + 1}/${this.pageCount} non calée (clique sur "Ajuster calage VAC")`;
    }

    if (lineEl) lineEl.textContent = msg;
    if (modalStatusEl) modalStatusEl.textContent = msg;
  },

  async openCalibratorModal() {
    if (this.pageCount === 0) {
      alert('Importe d’abord un fichier PDF VAC.');
      return;
    }
    const modal = document.getElementById('vac-calib-modal');
    if (modal) modal.classList.remove('hidden');
    this.updateStatusLine();
    await this.renderPreviewCanvas();
  },

  async renderPreviewCanvas() {
    const canvas = document.getElementById('vac-preview-canvas');
    if (!canvas) return;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.src = `https://appassets.androidplatform.net/vac/${this.vacId}/page_${this.activePage}.png?w=1024&t=${Date.now()}`;
    await new Promise((resolve) => {
      img.onload = resolve;
      img.onerror = resolve;
    });
    if (img.width > 0) {
      canvas.width = img.width;
      canvas.height = img.height;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0);

      // Dessine les points manuels sélectionnés
      const drawMarker = (pt, label, color) => {
        if (!pt) return;
        const px = pt.u * canvas.width;
        const py = pt.v * canvas.height;
        ctx.strokeStyle = color;
        ctx.lineWidth = 4;
        ctx.beginPath();
        ctx.arc(px, py, 16, 0, Math.PI * 2);
        ctx.stroke();
        ctx.fillStyle = color;
        ctx.font = 'bold 22px sans-serif';
        ctx.fillText(label, px + 20, py + 8);
      };
      drawMarker(this.manualPt1, 'P1', '#ef4444');
      drawMarker(this.manualPt2, 'P2', '#22c55e');
    }
  },

  startManualTwoPoint() {
    this.manualStep = 1;
    this.manualPt1 = null;
    this.manualPt2 = null;
    const stepEl = document.getElementById('vac-2pt-step');
    const actEl = document.getElementById('vac-2pt-actions');
    if (stepEl) {
      stepEl.classList.remove('hidden');
      stepEl.textContent = 'Étape 1/4 : Touche le 1er repère sur l’aperçu VAC ci-dessus (ex. seuil de piste ou point N/S).';
    }
    if (actEl) actEl.classList.remove('hidden');
    this.renderPreviewCanvas();
  },

  handlePreviewTap(u, v) {
    const stepEl = document.getElementById('vac-2pt-step');
    const reticle = document.getElementById('reticle');
    const modal = document.getElementById('vac-calib-modal');

    if (this.manualStep === 1) {
      this.manualPt1 = { u, v, lat: null, lon: null };
      this.manualStep = 2;
      this.renderPreviewCanvas();
      if (reticle) reticle.classList.remove('hidden');
      if (modal) modal.classList.add('hidden');
      if (stepEl) {
        stepEl.textContent = 'Étape 2/4 : Aligne le réticule rouge (+) sur le même repère sur la carte OACI, puis rouvre Caler -> Valider.';
      }
      alert('Repère 1 enregistré sur la VAC. Aligne maintenant le réticule rouge (+) au centre de la carte OACI sur ce même repère, puis clique sur "Caler" -> "Valider position réticule OACI".');
    } else if (this.manualStep === 3) {
      this.manualPt2 = { u, v, lat: null, lon: null };
      this.manualStep = 4;
      this.renderPreviewCanvas();
      if (reticle) reticle.classList.remove('hidden');
      if (modal) modal.classList.add('hidden');
      alert('Repère 2 enregistré sur la VAC. Aligne maintenant le réticule rouge (+) sur le 2e repère sur la carte OACI, puis clique sur "Caler" -> "Valider position réticule OACI".');
    }
  },

  confirmMapReticleForManualStep(centerLat, centerLon) {
    const stepEl = document.getElementById('vac-2pt-step');
    const reticle = document.getElementById('reticle');

    if (this.manualStep === 2 && this.manualPt1) {
      this.manualPt1.lat = centerLat;
      this.manualPt1.lon = centerLon;
      this.manualStep = 3;
      if (reticle) reticle.classList.add('hidden');
      if (stepEl) {
        stepEl.textContent = 'Étape 3/4 : Touche maintenant un 2e repère éloigné sur l’aperçu VAC ci-dessus.';
      }
    } else if (this.manualStep === 4 && this.manualPt2 && this.manualPt1) {
      this.manualPt2.lat = centerLat;
      this.manualPt2.lon = centerLon;
      const corners = computeVacCornersFromTwoPoints(this.manualPt1, this.manualPt2);
      if (corners) {
        this.calibrationsByPage[this.activePage] = {
          ...corners,
          method: 'MANUEL 2 PTS',
          confidence: 100
        };
        this.visible = true;
        this.manualStep = 0;
        if (reticle) reticle.classList.add('hidden');
        if (stepEl) stepEl.textContent = 'Calage manuel 2 points validé et sauvegardé !';
        this.persist();
        this.populatePageSelector();
        this.updateStatusLine();
        if (window.refreshVacOverlayOnMap) window.refreshVacOverlayOnMap();
      }
    }
  }
};
