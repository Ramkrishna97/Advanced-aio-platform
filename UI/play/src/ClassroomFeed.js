import { useCallback, useEffect, useState } from 'react';
import { Bookmark, FileText, MessageCircle, NotebookPen, PlusCircle, Upload, Video } from 'lucide-react';
import { api } from './api';
import ClassroomNavigation from './ClassroomNavigation';
import QuizPlayer from './QuizPlayer';

const call = (url, token, options = {}) => api(url, options, token);
const blankQuestion = () => ({ question: '', options: ['', '', '', ''], correctAnswer: 0 });
const blankQuiz = () => ({ module: '', subject: '', topic: '', timeMinutes: 15, questions: [blankQuestion()] });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const MAX_VIDEO_BYTES = Number(process.env.REACT_APP_MAX_VIDEO_BYTES || 2 * 1000 * 1000 * 1000);
const MAX_VIDEO_MB = Math.max(1, Math.floor(MAX_VIDEO_BYTES / 1000 / 1000));

async function uploadMedia(file, token, key, onProgress) {
  const stored = JSON.parse(localStorage.getItem(key) || 'null');
  const uploadId = stored?.name === file.name && stored?.size === file.size && stored?.lastModified === file.lastModified ? stored.id : crypto.randomUUID();
  localStorage.setItem(key, JSON.stringify({ id: uploadId, name: file.name, size: file.size, lastModified: file.lastModified }));
  const chunkSize = 1024 * 1024;
  const chunks = Math.ceil(file.size / chunkSize);
  const existing = new Set(await call(`/api/feed/media/uploads/${uploadId}`, token));
  for (let index = 0; index < chunks; index++) {
    if (existing.has(index)) { onProgress(Math.round((index + 1) / chunks * 100)); continue; }
    let attempt = 0;
    while (true) {
      try {
        await call(`/api/feed/media/uploads/${uploadId}/${index}`, token, { method: 'PUT', body: file.slice(index * chunkSize, (index + 1) * chunkSize), headers: { 'Content-Type': 'application/octet-stream' }, timeout: null });
        break;
      } catch (error) {
        if (error.status === 401 || error.status === 403 || ++attempt >= 3) throw error;
        await pause(attempt * 1000);
      }
    }
    onProgress(Math.round((index + 1) / chunks * 100));
  }
  const result = await call(`/api/feed/media/uploads/${uploadId}/complete`, token, { method: 'POST', body: JSON.stringify({ name: file.name, type: file.type, size: file.size, chunks }), timeout: null });
  localStorage.removeItem(key);
  return result;
}

function PostVideo({ post, token }) {
  const [source, setSource] = useState(post.resourceUrl || '');
  useEffect(() => {
    if (post.resourceUrl || !post.legacyTopicId || !post.type || post.type !== 'VIDEO') return undefined;
    let active = true;
    api(`/api/classroom/topics/${post.legacyTopicId}/video-ticket`, {}, token)
      .then(result => { if (active) setSource(result.url); })
      .catch(() => {});
    return () => { active = false; };
  }, [post.legacyTopicId, post.resourceUrl, post.type, token]);
  return source?.startsWith('/') || source?.startsWith('blob:') ? <video className="feed-video" controls preload="metadata" src={source} /> : source ? <a href={source} target="_blank" rel="noreferrer"><Video size={16} /> Open video lesson</a> : <p className="muted">Video is not available yet.</p>;
}

function Discussion({ post, token, admin }) {
  const [comments, setComments] = useState([]); const [text, setText] = useState(''); const [note, setNote] = useState('');
  useEffect(() => { call(`/api/feed/${post.id}/comments`, token).then(setComments).catch(() => {}); }, [post.id, token]);
  const submit = async event => { event.preventDefault(); const item = await call(`/api/feed/${post.id}/comments`, token, { method: 'POST', body: JSON.stringify({ text }) }); setComments(current => [...current, item]); setText(''); };
  const editComment = async item => { const updatedText = window.prompt('Edit comment', item.text); if (!updatedText?.trim()) return; const updated = await call(`/api/feed/${post.id}/comments/${item.id}`, token, { method: 'PUT', body: JSON.stringify({ text: updatedText }) }); setComments(current => current.map(comment => comment.id === item.id ? updated : comment)); };
  return <div className="post-discussion"><div>{comments.map(item => <p key={item.id}><b>{item.authorName}</b> {item.text} {admin && <><button type="button" className="text-button" onClick={() => editComment(item)}>Edit</button><button type="button" className="text-button" onClick={async () => { await call(`/api/feed/${post.id}/comments/${item.id}`, token, { method: 'DELETE' }); setComments(current => current.filter(c => c.id !== item.id)); }}>Delete</button></>}</p>)}</div><form className="form" onSubmit={submit}><input required value={text} placeholder="Write a comment" onChange={event => setText(event.target.value)} /><button className="text-button"><MessageCircle size={15} /> Comment</button></form><form className="form" onSubmit={async event => { event.preventDefault(); await call(`/api/feed/${post.id}/notes`, token, { method: 'POST', body: JSON.stringify({ text: note }) }); setNote(''); }}><input required value={note} placeholder="Private note" onChange={event => setNote(event.target.value)} /><button className="text-button"><NotebookPen size={15} /> Note</button></form></div>;
}

export default function ClassroomFeed({ token, user, studioInitially = false, modules, lessons, savedLessons, onModule, onLesson }) {
  const teacher = ['TEACHER', 'ADMIN'].includes(user?.role);
  const draftKey = `oneplace_studio_draft_${user?.id}`;
  const mediaKey = `oneplace_studio_upload_${user?.id}`;
  const savedKey = `oneplace_feed_bookmarks_${user?.id}`;
  const [studio, setStudio] = useState(studioInitially);
  const [posts, setPosts] = useState([]); const [page, setPage] = useState(0); const [next, setNext] = useState(false); const [loading, setLoading] = useState(true);
  const [error, setError] = useState(''); const [progress, setProgress] = useState(''); const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState(() => { try { return JSON.parse(localStorage.getItem(draftKey))?.draft || { title: '', body: '', type: 'POST', resourceUrl: '', quizId: '' }; } catch { return { title: '', body: '', type: 'POST', resourceUrl: '', quizId: '' }; } });
  const [draftId, setDraftId] = useState(() => { try { return JSON.parse(localStorage.getItem(draftKey))?.id || null; } catch { return null; } });
  const [media, setMedia] = useState(null);
  const [quiz, setQuiz] = useState(() => { try { return JSON.parse(localStorage.getItem(draftKey))?.quiz || blankQuiz(); } catch { return blankQuiz(); } });
  const [savedPosts, setSavedPosts] = useState(() => { try { return JSON.parse(localStorage.getItem(savedKey) || '[]'); } catch { return []; } });
  const [activeQuiz, setActiveQuiz] = useState(null);
  const load = useCallback(async (target = 0) => { try { const data = await call(`/api/feed?page=${target}&size=8`, token); setPosts(current => target ? [...current, ...data.items] : data.items); setPage(target); setNext(data.hasNext); setError(''); } catch (err) { setError(err.message); } finally { setLoading(false); } }, [token]);
  useEffect(() => { if (draft.title || draft.body || draftId) localStorage.setItem(draftKey, JSON.stringify({ draft, quiz, id: draftId })); else localStorage.removeItem(draftKey); }, [draft, quiz, draftId, draftKey]);
  useEffect(() => { localStorage.setItem(savedKey, JSON.stringify(savedPosts)); }, [savedPosts, savedKey]);
  useEffect(() => { setStudio(studioInitially); }, [studioInitially]); useEffect(() => { load(); }, [load]);
  const updateQuestion = (index, change) => setQuiz(current => ({ ...current, questions: current.questions.map((item, i) => i === index ? { ...item, ...change } : item) }));
  const updateOption = (questionIndex, optionIndex, value) => setQuiz(current => ({ ...current, questions: current.questions.map((item, i) => i === questionIndex ? { ...item, options: item.options.map((option, j) => j === optionIndex ? value : option) } : item) }));
  const importQuiz = async event => { const file = event.target.files?.[0]; if (!file) return; try { const source = JSON.parse(await file.text()); const value = Array.isArray(source) ? { questions: source } : source; if (!Array.isArray(value.questions) || !value.questions.length) throw new Error('The JSON file needs a non-empty questions array.'); const questions = value.questions.map(item => ({ question: item.question || '', options: Array.isArray(item.options) ? item.options.slice(0, 4) : [], correctAnswer: Number.isInteger(item.correctAnswer) ? item.correctAnswer : 0 })); if (questions.some(item => item.options.length !== 4)) throw new Error('Each imported question needs exactly four options.'); setQuiz({ module: value.module || '', subject: value.subject || '', topic: value.topic || value.name || '', timeMinutes: value.timeMinutes || 15, questions }); if (value.name) setDraft(current => ({ ...current, title: value.name })); setError(''); } catch (err) { setError(err.message || 'Could not read that quiz JSON file.'); } finally { event.target.value = ''; } };
  const publish = async event => {
    event.preventDefault(); if (busy) return; setBusy(true); setError('');
    try {
      if (draft.type === 'VIDEO' && media?.size > MAX_VIDEO_BYTES) throw new Error(`Videos must be ${MAX_VIDEO_MB} MB or smaller.`);
      setProgress('Saving draft…');
      const saved = draftId ? await call(`/api/studio/${draftId}`, token, { method: 'PUT', body: JSON.stringify(draft) }) : await call('/api/studio', token, { method: 'POST', body: JSON.stringify(draft) });
      setDraftId(saved.id);
      let resourceUrl = draft.resourceUrl; let quizId = draft.quizId;
      if (media) {
        setProgress('Upload queued. Keep this tab open while it transfers.');
        const uploaded = await uploadMedia(media, token, mediaKey, percent => setProgress(`Uploading ${media.name}: ${percent}%`));
        resourceUrl = uploaded.url;
        setDraft(current => ({ ...current, resourceUrl }));
      } else if ((draft.type === 'VIDEO' || draft.type === 'IMAGE') && !resourceUrl) {
        throw new Error('Select the video or image again to resume the saved upload.');
      }
      if (draft.type === 'QUIZ' && !quizId) {
        const created = await call('/api/quizzes', token, { method: 'POST', body: JSON.stringify({ name: draft.title, ...quiz }) });
        quizId = String(created.id); setDraft(current => ({ ...current, quizId }));
      }
      setProgress('Publishing saved draft…');
      await call(`/api/studio/${saved.id}`, token, { method: 'PUT', body: JSON.stringify({ ...draft, resourceUrl, quizId }) });
      await call(`/api/studio/${saved.id}/publish`, token, { method: 'POST' });
      setDraftId(null); setDraft({ title: '', body: '', type: 'POST', resourceUrl: '', quizId: '' }); setQuiz(blankQuiz()); setMedia(null); setStudio(false); setProgress('');
      localStorage.removeItem(draftKey); await load();
    } catch (err) { setError(err.message); setProgress('Upload or publish paused. Your draft and uploaded chunks are saved; retry when ready.'); }
    finally { setBusy(false); }
  };
  const toggleSaved = post => setSavedPosts(current => current.some(item => item.id === post.id) ? current.filter(item => item.id !== post.id) : [...current, { id: post.id, title: post.title }]);
  const showSaved = async id => { setStudio(false); try { const post = await call(`/api/feed/${id}`, token); setPosts(current => current.some(item => item.id === id) ? current : [post, ...current]); setTimeout(() => document.getElementById(`classroom-post-${id}`)?.scrollIntoView({ behavior: 'smooth' }), 0); } catch (err) { setError(err.message); } };
  const navigation = <ClassroomNavigation modules={modules} lessons={lessons} savedLessons={savedLessons} savedPosts={savedPosts} onModule={onModule} onLesson={onLesson} onFeed={() => setStudio(false)} onSavedPost={showSaved} />;
  if (studio && teacher) return <>{navigation}<section className="classroom-surface studio-editor"><span className="eyebrow">STUDIO EDITOR</span><h1>Create a classroom post</h1><button type="button" className="text-button" onClick={() => setStudio(false)}>Back to posts</button><form className="form panel" onSubmit={publish}><input required value={draft.title} placeholder="Post title" onChange={event => setDraft(item => ({ ...item, title: event.target.value }))} /><select value={draft.type} onChange={event => { setDraft(item => ({ ...item, type: event.target.value, resourceUrl: '', quizId: '' })); setMedia(null); }}><option value="POST">Blog post</option><option value="VIDEO">Video lesson</option><option value="IMAGE">Image post</option><option value="RESOURCE">Resource link</option><option value="QUIZ">Quiz</option></select><textarea value={draft.body} placeholder="Write the post description" onChange={event => setDraft(item => ({ ...item, body: event.target.value }))} />{(draft.type === 'VIDEO' || draft.type === 'IMAGE') && <label>Upload {draft.type === 'VIDEO' ? 'a video' : 'an image'}<input required={!draft.resourceUrl} type="file" accept={draft.type === 'VIDEO' ? 'video/*' : 'image/*'} onChange={event => setMedia(event.target.files?.[0] || null)} />{media && <small>{media.name}</small>}</label>}{draft.type === 'RESOURCE' && <input required value={draft.resourceUrl} placeholder="Resource link" onChange={event => setDraft(item => ({ ...item, resourceUrl: event.target.value }))} />}{draft.type === 'QUIZ' && <QuizEditor quiz={quiz} setQuiz={setQuiz} updateQuestion={updateQuestion} updateOption={updateOption} importQuiz={importQuiz} />}{progress && <p className="service-waiting" role="status">{progress}</p>}{error && <p className="inline-error" role="alert">{error}</p>}<button className="primary" disabled={busy}>{busy ? 'Saving and uploading…' : 'Publish'}</button></form></section></>;
  return <>{navigation}<section className="classroom-surface"><div className="classroom-feed-actions"><div><span className="eyebrow">CLASSROOM</span><h1>Recent posts</h1></div>{teacher && <button className="primary small" onClick={() => setStudio(true)}><PlusCircle size={16} /> Create post</button>}</div>{error && <p className="inline-error" role="alert">{error}</p>}
    <div className="community-posts">{posts.map(post => <article id={`classroom-post-${post.id}`} className="feed-card classroom-post" key={post.id}>
      <header className="classroom-post-header"><span className="lesson-avatar">{post.authorName?.slice(0, 1).toUpperCase() || 'T'}</span><div><strong>{post.authorName || 'Unknown teacher'}</strong><time dateTime={post.createdAt || undefined}>{post.createdAt ? new Date(post.createdAt).toLocaleString() : 'Date unavailable'}</time></div><button type="button" className="text-button" aria-label={savedPosts.some(item => item.id === post.id) ? 'Remove saved post' : 'Save post'} onClick={() => toggleSaved(post)}><Bookmark size={18} fill={savedPosts.some(item => item.id === post.id) ? 'currentColor' : 'none'} /></button></header>
      <div className="classroom-post-content"><h2>{post.title}</h2>{post.body && <p>{post.body}</p>}</div>
      {post.type === 'VIDEO' && <PostVideo post={post} token={token} />}
      {post.type === 'IMAGE' && <img className="feed-image" src={post.resourceUrl} alt={post.title} />}
      {post.type === 'RESOURCE' && <p className="classroom-post-content"><FileText size={16} /> <a href={post.resourceUrl} target="_blank" rel="noreferrer">Open resource</a></p>}
      {post.type === 'QUIZ' && <div className="classroom-post-content"><button type="button" className="primary small" onClick={() => setActiveQuiz(post.id)}>{teacher ? 'Preview quiz' : 'Open quiz'}</button>{activeQuiz === post.id && <QuizPlayer quizId={post.quizId} token={token} teacher={teacher} onClose={() => setActiveQuiz(null)} />}</div>}
      <Discussion post={post} token={token} admin={user?.role === 'ADMIN'} />
    </article>)}</div>{!posts.length && !error && <p className="feed-loading">{loading ? 'Loading recent activity…' : 'No posts yet. Teachers can publish the first post.'}</p>}{next && <button className="text-button load-more" onClick={() => load(page + 1)}>Load more posts</button>}</section></>;
}

function QuizEditor({ quiz, setQuiz, updateQuestion, updateOption, importQuiz }) {
  return <div className="quiz-authoring"><div className="section-title"><div><h2>Quiz details</h2><p>Build a quiz here or import its JSON definition.</p></div><label className="text-button"><Upload size={15} /> Import JSON<input hidden type="file" accept="application/json,.json" onChange={importQuiz} /></label></div><div className="form-row"><label>Module<input required value={quiz.module} onChange={event => setQuiz(item => ({ ...item, module: event.target.value }))} /></label><label>Subject<input required value={quiz.subject} onChange={event => setQuiz(item => ({ ...item, subject: event.target.value }))} /></label></div><div className="form-row"><label>Topic<input required value={quiz.topic} onChange={event => setQuiz(item => ({ ...item, topic: event.target.value }))} /></label><label>Time (minutes)<input required min="1" type="number" value={quiz.timeMinutes} onChange={event => setQuiz(item => ({ ...item, timeMinutes: Number(event.target.value) }))} /></label></div>{quiz.questions.map((item, questionIndex) => <fieldset key={questionIndex}><legend>Question {questionIndex + 1}</legend><label>Question<input required value={item.question} onChange={event => updateQuestion(questionIndex, { question: event.target.value })} /></label>{item.options.map((option, optionIndex) => <label key={optionIndex}>Option {optionIndex + 1}<input required value={option} onChange={event => updateOption(questionIndex, optionIndex, event.target.value)} /></label>)}<label>Correct option<select value={item.correctAnswer} onChange={event => updateQuestion(questionIndex, { correctAnswer: Number(event.target.value) })}>{item.options.map((_, optionIndex) => <option key={optionIndex} value={optionIndex}>Option {optionIndex + 1}</option>)}</select></label>{quiz.questions.length > 1 && <button type="button" className="text-button" onClick={() => setQuiz(current => ({ ...current, questions: current.questions.filter((_, index) => index !== questionIndex) }))}>Remove question</button>}</fieldset>)}<button type="button" className="text-button" onClick={() => setQuiz(current => ({ ...current, questions: [...current.questions, blankQuestion()] }))}>Add another question</button></div>;
}
