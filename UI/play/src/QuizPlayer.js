import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';

export default function QuizPlayer({ quizId, token, teacher, onClose }) {
  const [quiz, setQuiz] = useState(null);
  const [attemptId, setAttemptId] = useState(null);
  const [remaining, setRemaining] = useState(null);
  const [answers, setAnswers] = useState({});
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const autoSubmitted = useRef(false);

  useEffect(() => { let active = true; api(`/api/quizzes/${quizId}`, {}, token).then(value => { if (active) setQuiz(value); }).catch(err => { if (active) setError(err.message); }); return () => { active = false; }; }, [quizId, token]);
  const start = async () => { setBusy(true); setError(''); try { const data = await api(`/api/quizzes/${quizId}/start`, { method: 'POST' }, token); setQuiz(data.quiz); setAttemptId(data.attemptId); setRemaining(data.remainingSeconds); } catch (err) { setError(err.message); } finally { setBusy(false); } };
  const submit = useCallback(async () => { if (!attemptId || busy || result) return; setBusy(true); try { const data = await api(`/api/quizzes/attempts/${attemptId}/submit`, { method: 'POST', body: JSON.stringify({ answers, warnings: 0 }) }, token); setResult(data); } catch (err) { setError(err.message); } finally { setBusy(false); } }, [answers, attemptId, busy, result, token]);
  useEffect(() => { if (remaining === null || result || !attemptId) return undefined; if (remaining <= 0) { if (!autoSubmitted.current) { autoSubmitted.current = true; submit(); } return undefined; } const timer = setTimeout(() => setRemaining(value => Math.max(0, value - 1)), 1000); return () => clearTimeout(timer); }, [remaining, result, attemptId, submit]);

  return <section className="quiz-player" aria-label="Quiz"><div className="section-title"><div><span className="eyebrow">{teacher ? 'TEACHER PREVIEW' : 'CLASSROOM QUIZ'}</span><h3>{quiz?.name || 'Loading quiz…'}</h3></div><button type="button" className="text-button" onClick={onClose}>Close</button></div>{error && <p className="inline-error" role="alert">{error}</p>}{quiz && <><p className="muted">{[quiz.module, quiz.subject, quiz.topic].filter(Boolean).join(' · ')} · {quiz.timeMinutes || 15} minutes</p>{!teacher && !attemptId && <button type="button" className="primary small" onClick={start} disabled={busy}>Start quiz</button>}{remaining !== null && !result && <p className="quiz-timer" role="timer">Time left: {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, '0')}</p>}{(teacher || attemptId) && <>{quiz.questions.map((item, index) => <fieldset key={item.id} disabled={!!result || teacher}><legend>{index + 1}. {item.question}</legend>{item.options.map((option, choice) => <label key={choice}><input type="radio" name={`quiz-${quizId}-${item.id}`} checked={answers[item.id] === choice} onChange={() => setAnswers(current => ({ ...current, [item.id]: choice }))} /> {option}{teacher && item.correctAnswer === choice && ' (correct)'}</label>)}</fieldset>)}{!teacher && !result && <button type="button" className="primary small" disabled={busy} onClick={submit}>{busy ? 'Submitting…' : 'Submit answers'}</button>}</>}{result && <p className="success-message" role="status">Score: {result.right} of {result.analysis.length} correct · {result.wrong} wrong · {result.skipped} skipped</p>}</>}</section>;
}
