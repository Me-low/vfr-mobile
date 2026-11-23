// ============================================================================
// GÉODÉSIE AÉRONAUTIQUE : Lambert Conforme Conique (RGF93), Calage OACI & VAC
// ============================================================================

const DEG2RAD = Math.PI / 180;
const RAD2DEG = 180 / Math.PI;
const EARTH_RADIUS_M = 6371008.8;
const MPS_TO_KT = 1.9438444924406046;
const M_TO_FT = 3.280839895013123;

// Paramètres officiels RGF93 / Lambert-93 (projection standard des cartes OACI France)
const LCC_PARAMS = (() => {
  const a = 6378137.0; // GRS80 semi-major axis
  const f = 1 / 298.257222101;
  const e = Math.sqrt(2 * f - f * f);
  const phi1 = 44.0 * DEG2RAD;
  const phi2 = 49.0 * DEG2RAD;
  const phi0 = 46.5 * DEG2RAD;
  const lambda0 = 3.0 * DEG2RAD;

  const m = (phi) => Math.cos(phi) / Math.sqrt(1 - e * e * Math.sin(phi) ** 2);
  const t = (phi) =>
    Math.tan(Math.PI / 4 - phi / 2) /
    Math.pow((1 - e * Math.sin(phi)) / (1 + e * Math.sin(phi)), e / 2);

  const m1 = m(phi1);
  const m2 = m(phi2);
  const t1 = t(phi1);
  const t2 = t(phi2);
  const t0 = t(phi0);

  const n = Math.log(m1 / m2) / Math.log(t1 / t2);
  const F = m1 / (n * Math.pow(t1, n));
  const rho0 = a * F * Math.pow(t0, n);

  return { a, e, n, F, rho0, lambda0, t };
})();

/** Projection directe WGS84 (lat, lon) -> Lambert-93 (xEasting, yNorthing) en mètres */
function geoToLambert(latDeg, lonDeg) {
  const { a, F, n, rho0, lambda0, t } = LCC_PARAMS;
  const phi = Math.max(-85, Math.min(85, latDeg)) * DEG2RAD;
  const lambda = lonDeg * DEG2RAD;
  const rho = a * F * Math.pow(t(phi), n);
  const theta = n * (lambda - lambda0);
  const x = rho * Math.sin(theta);
  const y = rho0 - rho * Math.cos(theta);
  return [x, y];
}

/** Projection inverse Lambert-93 (xEasting, yNorthing) -> WGS84 (lat, lon) en degrés */
function lambertToGeo(x, y) {
  const { a, e, F, n, rho0, lambda0 } = LCC_PARAMS;
  const dy = rho0 - y;
  const rho = Math.sign(n) * Math.hypot(x, dy);
  const theta = Math.atan2(x, dy);
  const lon = (lambda0 + theta / n) * RAD2DEG;

  if (Math.abs(rho) < 1e-9) {
    return [Math.sign(n) * 90, lon];
  }

  const tVal = Math.pow(rho / (a * F), 1 / n);
  let phi = Math.PI / 2 - 2 * Math.atan(tVal);
  for (let i = 0; i < 8; i++) {
    const esin = e * Math.sin(phi);
    phi =
      Math.PI / 2 -
      2 * Math.atan(tVal * Math.pow((1 - esin) / (1 + esin), e / 2));
  }
  return [phi * RAD2DEG, lon];
}

function haversineNm(lat1, lon1, lat2, lon2) {
  const dLat = (lat2 - lat1) * DEG2RAD;
  const dLon = (lon2 - lon1) * DEG2RAD;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * DEG2RAD) * Math.cos(lat2 * DEG2RAD) * Math.sin(dLon / 2) ** 2;
  const meters = 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
  return meters / 1852;
}

function initialBearingDeg(lat1, lon1, lat2, lon2) {
  const p1 = lat1 * DEG2RAD;
  const p2 = lat2 * DEG2RAD;
  const dl = (lon2 - lon1) * DEG2RAD;
  const y = Math.sin(dl) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return (Math.atan2(y, x) * RAD2DEG + 360) % 360;
}

function destinationPoint(latDeg, lonDeg, bearingDeg, distanceNm) {
  const d = (distanceNm * 1852) / EARTH_RADIUS_M;
  const brng = bearingDeg * DEG2RAD;
  const p1 = latDeg * DEG2RAD;
  const l1 = lonDeg * DEG2RAD;

  const p2 = Math.asin(
    Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(brng)
  );
  const l2 =
    l1 +
    Math.atan2(
      Math.sin(brng) * Math.sin(d) * Math.cos(p1),
      Math.cos(d) - Math.sin(p1) * Math.sin(p2)
    );
  return [p2 * RAD2DEG, ((l2 * RAD2DEG + 540) % 360) - 180];
}

function mpsToKnots(mps) {
  return Number.isFinite(mps) && mps >= 0 ? mps * MPS_TO_KT : null;
}

function metersToFeet(meters) {
  return Number.isFinite(meters) ? meters * M_TO_FT : null;
}

function pressureToAltitudeFt(pressureHpa, refHpa = 1013.25) {
  if (!Number.isFinite(pressureHpa) || pressureHpa <= 0) return null;
  return 145366.45 * (1 - Math.pow(pressureHpa / refHpa, 0.190284));
}

/**
 * Résout par moindres carrés la transformation conforme (similitude 2D) entre
 * N >= 2 points Lambert (E, N) et PDF (xPdf, yPdf) :
 *   xPdf = a * E - b * N + tx
 *   yPdf = b * E + a * N + ty
 */
function solveConformalCalibration(gcps) {
  if (!Array.isArray(gcps) || gcps.length < 2) return null;

  const pts = gcps.map((g) => {
    const [E, N] = geoToLambert(g.lat, g.lon);
    return { E, N, x: g.xPdf, y: g.yPdf, label: g.label || '' };
  });

  const n = pts.length;
  let meanE = 0, meanN = 0, meanX = 0, meanY = 0;
  for (const p of pts) {
    meanE += p.E;
    meanN += p.N;
    meanX += p.x;
    meanY += p.y;
  }
  meanE /= n;
  meanN /= n;
  meanX /= n;
  meanY /= n;

  let numA = 0, numB = 0, denom = 0;
  for (const p of pts) {
    const dE = p.E - meanE;
    const dN = p.N - meanN;
    const dX = p.x - meanX;
    const dY = p.y - meanY;
    numA += dE * dX + dN * dY;
    numB += dE * dY - dN * dX;
    denom += dE * dE + dN * dN;
  }

  if (denom < 1e-6) return null;

  const a = numA / denom;
  const b = numB / denom;
  const tx = meanX - (a * meanE - b * meanN);
  const ty = meanY - (b * meanE + a * meanN);
  const scale = Math.hypot(a, b);
  const rotationDeg = Math.atan2(b, a) * RAD2DEG;

  let sumSqMeters = 0;
  for (const p of pts) {
    const predX = a * p.E - b * p.N + tx;
    const predY = b * p.E + a * p.N + ty;
    const errPx = Math.hypot(predX - p.x, predY - p.y);
    const errM = errPx / scale;
    sumSqMeters += errM * errM;
  }

  return {
    a,
    b,
    tx,
    ty,
    scale,
    rotationDeg,
    rmsMeters: Math.sqrt(sumSqMeters / n),
    pointCount: n
  };
}

/** Applique la calibration Lambert -> PDF (xPdf, yPdf) */
function forwardCalibration(calib, lat, lon) {
  const [E, N] = geoToLambert(lat, lon);
  return [
    calib.a * E - calib.b * N + calib.tx,
    calib.b * E + calib.a * N + calib.ty
  ];
}

/** Applique la calibration inverse PDF (xPdf, yPdf) -> (lat, lon) */
function inverseCalibration(calib, xPdf, yPdf) {
  const det = calib.a * calib.a + calib.b * calib.b;
  if (det < 1e-24) return [46.5, 0.0];
  const dx = xPdf - calib.tx;
  const dy = yPdf - calib.ty;
  const E = (calib.a * dx + calib.b * dy) / det;
  const N = (-calib.b * dx + calib.a * dy) / det;
  return lambertToGeo(E, N);
}

/**
 * Calcule une similitude 2D entre 2 points normalisés VAC (u, v in [0,1])
 * et 2 points Lambert (E, N), puis retourne les 4 coins géographiques [lat, lon]
 * dans l'ordre MapLibre : [topLeft, topRight, bottomRight, bottomLeft].
 */
function computeVacCornersFromTwoPoints(p1, p2) {
  const [E1, N1] = geoToLambert(p1.lat, p1.lon);
  const [E2, N2] = geoToLambert(p2.lat, p2.lon);

  const du = p2.u - p1.u;
  const dv = p2.v - p1.v;
  const dE = E2 - E1;
  const dN = N2 - N1;

  const denom = du * du + dv * dv;
  if (denom < 1e-10) return null;

  const a = (dE * du + dN * dv) / denom;
  const b = (dN * du - dE * dv) / denom;
  const tE = E1 - (a * p1.u - b * p1.v);
  const tN = N1 - (b * p1.u + a * p1.v);

  const uvToGeo = (u, v) => {
    const E = a * u - b * v + tE;
    const N = b * u + a * v + tN;
    return lambertToGeo(E, N);
  };

  return {
    topLeft: uvToGeo(0, 0),
    topRight: uvToGeo(1, 0),
    bottomRight: uvToGeo(1, 1),
    bottomLeft: uvToGeo(0, 1),
    rotationDeg: Math.atan2(b, a) * RAD2DEG,
    metersPerWidth: Math.hypot(a, b)
  };
}
