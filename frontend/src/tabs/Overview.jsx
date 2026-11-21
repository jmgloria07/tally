import { api } from '../api.js';
import { fmt, percent } from '../format.js';
import { useLoad } from '../useLoad.js';
import Progress from '../components/Progress.jsx';
import WeightChart from '../components/WeightChart.jsx';

const OVERVIEW_CHIPS = [
  {
    label: 'What should I focus on?',
    prompt: 'Look at my recent logs. What is one useful thing I should focus on next?',
  },
  {
    label: 'Plan my next workout',
    prompt:
      'Help me plan my next workout based on my recent workout log. If the data is incomplete, ask me what you need.',
  },
];

const ACTIVITY_ICONS = { workout: ['↗', ''], meal: ['◉', 'food'], weighIn: ['⌁', 'weight'] };

export default function Overview({ authEpoch, navigate, askCoach }) {
  const { data: d, error, loading } = useLoad(() => api('/api/dashboard'), authEpoch);

  if (!d) {
    return (
      <section className="page">
        {loading ? <div className="empty-state">Loading…</div> : <div className="empty-state">{error}</div>}
      </section>
    );
  }

  const t = d.todayTotals;
  const g = d.targets;
  return (
    <section className="page">
      <div className="welcome-row">
        <div>
          <div className="eyebrow accent">CONSISTENCY OVER PERFECTION</div>
          <h2>
            Small actions. <em>Real progress.</em>
          </h2>
          <p className="subtle">Your daily snapshot, based on what you've logged.</p>
        </div>
        <button type="button" className="button button-primary" onClick={() => navigate('workout')}>
          ＋ Log workout
        </button>
      </div>

      <div className="stats-grid">
        <article className="stat-card">
          <div className="stat-top">
            <span>CALORIES TODAY</span>
            <span className="stat-symbol">◌</span>
          </div>
          <div className="stat-value">
            <span>{fmt(t.calories)}</span>
            <small>kcal</small>
          </div>
          <Progress value={percent(t.calories, g.calories)} color="orange" />
          <div className="stat-foot">
            <span>{fmt(Math.max(0, g.calories - t.calories))} kcal remaining</span>
            <span>{Math.round(percent(t.calories, g.calories))}%</span>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-top">
            <span>PROTEIN TODAY</span>
            <span className="stat-symbol">⌁</span>
          </div>
          <div className="stat-value">
            <span>{fmt(t.proteinG, 1)}</span>
            <small>g</small>
          </div>
          <Progress value={percent(t.proteinG, g.proteinG)} color="green" />
          <div className="stat-foot">
            <span>{fmt(Math.max(0, g.proteinG - t.proteinG), 1)} g to target</span>
            <span>{Math.round(percent(t.proteinG, g.proteinG))}%</span>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-top">
            <span>TRAINING DAYS</span>
            <span className="stat-symbol">↗</span>
          </div>
          <div className="stat-value">
            <span>{d.workoutDaysLast7}</span>
            <small>/ 7 days</small>
          </div>
          <div className="stat-foot simple">
            <span>Last seven days</span>
            <button type="button" className="text-button" onClick={() => navigate('workout')}>
              View log →
            </button>
          </div>
        </article>
        <article className="stat-card">
          <div className="stat-top">
            <span>LATEST WEIGHT</span>
            <span className="stat-symbol">⌁</span>
          </div>
          <div className="stat-value">
            <span>{d.latestWeighIn ? fmt(d.latestWeighIn.weightKg, 1) : '—'}</span>
            <small>kg</small>
          </div>
          <div className="stat-foot simple">
            <span>{d.latestWeighIn ? `Logged ${d.latestWeighIn.date}` : 'No weigh-in yet'}</span>
            <button type="button" className="text-button" onClick={() => navigate('body')}>
              Update →
            </button>
          </div>
        </article>
      </div>

      <div className="content-grid overview-grid">
        <article className="panel nutrition-panel">
          <div className="panel-heading">
            <div>
              <div className="eyebrow">DAILY NUTRITION</div>
              <h3>Macro check-in</h3>
            </div>
            <button type="button" className="text-button" onClick={() => navigate('nutrition')}>
              Log food →
            </button>
          </div>
          <div className="macro-list">
            <MacroRow label="Protein" dot="protein-dot" color="green" value={t.proteinG} target={g.proteinG} />
            <MacroRow label="Carbs" dot="carbs-dot" color="blue" value={t.carbsG} target={g.carbsG} />
            <MacroRow label="Fat" dot="fat-dot" color="violet" value={t.fatG} target={g.fatG} />
          </div>
          <div className="week-averages">
            <div className="tip-stat">
              <span>WEEKLY AVG. CALORIES</span>
              <b>
                {fmt(d.weekAvgCaloriesPerLoggedDay)} <small>kcal per logged day</small>
              </b>
            </div>
            <div className="tip-stat">
              <span>WEEKLY AVG. PROTEIN</span>
              <b>
                {fmt(d.weekAvgProteinPerLoggedDay, 1)} <small>g per logged day</small>
              </b>
            </div>
          </div>
          <div className="hint-box">
            <span>✳</span>
            <p>
              These totals include only the meals you've entered. Estimates are fine—consistency matters more than
              false precision.
            </p>
          </div>
        </article>

        <article className="panel coach-panel">
          <div className="coach-panel-head">
            <div className="coach-orb">✳</div>
            <div>
              <div className="eyebrow">LOCAL AI COACH</div>
              <h3>A little direction?</h3>
            </div>
          </div>
          <p>
            Ask about your workout, nutrition, or how to keep momentum. The coach can use your last 4 weeks of
            logged data.
          </p>
          <div className="quick-prompts">
            {OVERVIEW_CHIPS.map((chip) => (
              <button key={chip.label} type="button" className="prompt-chip" onClick={() => askCoach(chip.prompt)}>
                {chip.label}
              </button>
            ))}
          </div>
          <button type="button" className="button button-dark full-width" onClick={() => navigate('coach')}>
            Talk to your coach <span>→</span>
          </button>
        </article>
      </div>

      <article className="panel trend-panel overview-trend">
        <div className="eyebrow">RECENT TREND</div>
        <h3>Weight, last 30 days</h3>
        <WeightChart points={d.weightLast30} today={d.today} />
      </article>

      <div className="panel recent-panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">RECENT ACTIVITY</div>
            <h3>What you've logged</h3>
          </div>
        </div>
        <div className="activity-list">
          {d.recentActivity.length === 0 ? (
            <div className="empty-state">Nothing logged yet. Add a workout or meal to start building your history.</div>
          ) : (
            d.recentActivity.map((a, i) => {
              const [icon, cls] = ACTIVITY_ICONS[a.kind] || ['•', ''];
              return (
                <div className="activity-item" key={`${a.kind}-${a.date}-${i}`}>
                  <div className={`activity-icon ${cls}`}>{icon}</div>
                  <div className="activity-main">
                    <div className="activity-title">{a.title}</div>
                    <div className="activity-sub">{a.detail}</div>
                  </div>
                  <div className="activity-date">{a.date}</div>
                </div>
              );
            })
          )}
        </div>
      </div>

      <div className="disclaimer">
        FitLocal is a personal tracking tool, not a medical service. AI suggestions can be wrong; use judgment and
        consult a qualified professional for medical concerns.
      </div>
    </section>
  );
}

function MacroRow({ label, dot, color, value, target }) {
  return (
    <div className="macro-row">
      <div className="macro-name">
        <span className={`macro-dot ${dot}`} />
        {label}{' '}
        <b>
          {fmt(value, 1)} / {fmt(target)} g
        </b>
      </div>
      <Progress value={percent(value, target)} color={color} thin />
    </div>
  );
}
