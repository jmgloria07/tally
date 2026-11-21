import { useEffect, useRef, useState } from 'react';
import { daysBefore, fmt, round1 } from '../format.js';

const DAYS = 30;
const HEIGHT = 200;
const MARGIN = { top: 18, right: 14, bottom: 26, left: 42 };
const STEPS = [0.1, 0.2, 0.25, 0.5, 1, 2, 2.5, 5, 10, 20];

/**
 * Hand-drawn weight line chart: one slot per day for the last 30 days, a dot on each day with a weigh-in,
 * and a line connecting them across days without one. The y-axis is fitted to the weights in the window
 * (it does not start at 0), so a change of a few tenths of a kilo is visible.
 *
 * `points` are { date, weightKg }, oldest first; with several weigh-ins on one day, the last one counts.
 */
export default function WeightChart({ points, today }) {
  const [ref, width] = useWidth();
  const start = daysBefore(today, DAYS - 1);

  const byDay = new Map();
  points
    .filter((p) => p.date >= start && p.date <= today && Number.isFinite(Number(p.weightKg)))
    .forEach((p) => byDay.set(p.date, Number(p.weightKg)));
  const dates = Array.from({ length: DAYS }, (_, i) => daysBefore(today, DAYS - 1 - i));
  const series = dates.map((date, index) => ({ date, index, weight: byDay.get(date) })).filter((p) => p.weight !== undefined);

  if (series.length < 2) {
    return (
      <>
        <div className="weight-chart weight-chart-empty" ref={ref}>
          <div className="empty-state">Add two or more weigh-ins in the last 30 days to see your trend.</div>
        </div>
        <div className="chart-caption">No trend calculated yet.</div>
      </>
    );
  }

  const values = series.map((p) => p.weight);
  const { lo, hi, ticks } = yScale(Math.min(...values), Math.max(...values));
  const plotW = Math.max(10, width - MARGIN.left - MARGIN.right);
  const plotH = HEIGHT - MARGIN.top - MARGIN.bottom;
  const x = (index) => MARGIN.left + (index / (DAYS - 1)) * plotW;
  const y = (weight) => MARGIN.top + (1 - (weight - lo) / (hi - lo)) * plotH;
  const path = series.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.index).toFixed(1)},${y(p.weight).toFixed(1)}`).join(' ');
  const first = series[0];
  const last = series[series.length - 1];
  const delta = round1(last.weight - first.weight);
  const labelIndexes = [0, 7, 14, 21, DAYS - 1];
  const decimals = ticks.some((t) => !Number.isInteger(round1(t))) ? 1 : 0;

  return (
    <>
      <div className="weight-chart weight-line-chart" ref={ref}>
        <svg
          width={width}
          height={HEIGHT}
          role="img"
          aria-label={`Weight over the last 30 days: ${fmt(first.weight, 1)} kg on ${first.date}, ${fmt(last.weight, 1)} kg on ${last.date}.`}
        >
          {ticks.map((t) => (
            <g key={t}>
              <line className="wc-grid" x1={MARGIN.left} x2={MARGIN.left + plotW} y1={y(t)} y2={y(t)} />
              <text className="wc-axis" x={MARGIN.left - 6} y={y(t)} textAnchor="end" dominantBaseline="middle">
                {t.toFixed(decimals)}
              </text>
            </g>
          ))}
          {labelIndexes.map((i) => (
            <text key={i} className="wc-axis" x={x(i)} y={HEIGHT - 6} textAnchor={i === 0 ? 'start' : i === DAYS - 1 ? 'end' : 'middle'}>
              {dates[i].slice(5)}
            </text>
          ))}
          <path className="wc-line" d={path} />
          {series.map((p) => (
            <circle key={p.date} className="wc-dot" cx={x(p.index)} cy={y(p.weight)} r={p === last ? 4.5 : 3}>
              <title>{`${p.date}: ${fmt(p.weight, 1)} kg`}</title>
            </circle>
          ))}
          <text className="wc-value" x={x(last.index)} y={y(last.weight) - 10} textAnchor="end">
            {fmt(last.weight, 1)} kg
          </text>
        </svg>
      </div>
      <div className="chart-caption">
        {first.date} → {last.date}: {delta > 0 ? '+' : ''}
        {fmt(delta, 1)} kg over {series.length} weigh-ins. The scale starts near your lowest weight, so small changes
        look larger. Short-term changes can reflect hydration, food, and other normal fluctuations.
      </div>
    </>
  );
}

/** Padded range around the data (never forced to 0) and up to 5 evenly spaced "nice" tick values. */
function yScale(min, max) {
  const pad = Math.max((max - min) * 0.15, 0.2);
  const lo = min - pad;
  const hi = max + pad;
  const step = STEPS.find((s) => (hi - lo) / s <= 4) ?? STEPS[STEPS.length - 1];
  const ticks = [];
  for (let t = Math.ceil(lo / step) * step; t <= hi + 1e-9; t += step) {
    ticks.push(Math.round(t * 100) / 100);
  }
  return { lo, hi, ticks };
}

/** Measures the container so the SVG is drawn at real pixel size (text stays readable on phones). */
function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(320);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const update = () => setWidth(Math.max(200, Math.floor(el.getBoundingClientRect().width)));
    update();
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}
