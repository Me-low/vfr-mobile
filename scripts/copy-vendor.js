const fs = require('fs');
const path = require('path');

const vendorDir = path.join(__dirname, '..', 'www', 'vendor');
fs.mkdirSync(vendorDir, { recursive: true });

const copies = [
  ['node_modules/maplibre-gl/dist/maplibre-gl.js', 'maplibre-gl.js'],
  ['node_modules/maplibre-gl/dist/maplibre-gl.css', 'maplibre-gl.css'],
  ['node_modules/pdfjs-dist/build/pdf.min.js', 'pdf.min.js'],
  ['node_modules/pdfjs-dist/build/pdf.worker.min.js', 'pdf.worker.min.js']
];

for (const [srcRel, dstName] of copies) {
  const src = path.join(__dirname, '..', srcRel);
  const dst = path.join(vendorDir, dstName);
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dst);
    console.log('[vendor] Copied', dstName);
  } else {
    console.warn('[vendor] Missing optional file:', srcRel);
  }
}
