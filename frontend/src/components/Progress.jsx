// A progress bar. Width is set through React's style prop (CSSOM), which the CSP allows.
export default function Progress({ value, color, thin = false }) {
  const width = Math.max(0, Math.min(100, Number(value) || 0));
  return (
    <div className={`progress-track ${thin ? 'thin' : ''}`}>
      <div className={`progress-fill ${color}`} style={{ width: `${width}%` }} />
    </div>
  );
}
