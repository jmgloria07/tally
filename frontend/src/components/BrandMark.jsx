// Tally logo: four tally strokes crossed by a fifth. Same shape as public/favicon.svg.
export default function BrandMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 36 36" strokeWidth="2.6" strokeLinecap="round">
        <g className="brand-mark-strokes">
          <line x1="10" y1="10" x2="10" y2="26" />
          <line x1="15" y1="10" x2="15" y2="26" />
          <line x1="20" y1="10" x2="20" y2="26" />
          <line x1="25" y1="10" x2="25" y2="26" />
        </g>
        <line className="brand-mark-slash" x1="6.5" y1="23.5" x2="29" y2="12" />
      </svg>
    </div>
  );
}
