import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Bookmark, BookOpen, ChevronDown, ChevronRight, Heart, ImageOff, MoreHorizontal, MessageCircle, NotebookPen, PanelLeftClose, PlayCircle, Sparkles } from 'lucide-react';
import { recordActivity } from './api';
import ClassroomFeed from './ClassroomFeed';

const MAX_VIDEO_BYTES = Number(process.env.REACT_APP_MAX_VIDEO_BYTES || 2 * 1000 * 1000 * 1000);
const MAX_VIDEO_MB = Math.max(1, Math.floor(MAX_VIDEO_BYTES / 1000 / 1000));

const classroomMessage = (status) => status === 400 || status === 422 ? 'Please review the entered information and try again.'
  : status === 401 ? 'Your classroom session has expired. Please sign in again.'
  : status === 403 ? 'Your account does not have permission for that action.'
  : status === 404 ? 'The requested classroom item is no longer available.'
  : 'Classroom service is still starting. Please wait while it reconnects.';

function CommentHeader({ comment, user }) {
  const [imgError, setImgError] = useState(false);
  const photoUrl = comment.studentId
    ? `/api/auth/users/${comment.studentId}/photo${user?.id === comment.studentId && user?.photoUpdatedAt ? `?v=${new Date(user.photoUpdatedAt).getTime()}` : ''}`
    : null;

  useEffect(() => {
    setImgError(false);
  }, [photoUrl]);

  const formattedDate = comment.createdAt
    ? (() => {
        try {
          const d = new Date(comment.createdAt);
          return isNaN(d.getTime())
            ? ''
            : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
        } catch {
          return '';
        }
      })()
    : '';

  return (
    <div className="comment-header">
      <div className="comment-avatar">
        {photoUrl && !imgError ? (
          <img
            src={photoUrl}
            alt={comment.authorName || 'Member'}
            onError={() => setImgError(true)}
          />
        ) : (
          <span className="comment-avatar-initial" aria-label="Default profile"><Sparkles size={15} /></span>
        )}
      </div>
      <div className="comment-author-info">
        <span className="comment-author-name">{comment.authorName || 'Member'}</span>
        {formattedDate && <time className="comment-date">{formattedDate}</time>}
      </div>
    </div>
  );
}

function ResourcePlaceholder({ label }) {
  return <div className="resource-placeholder"><ImageOff size={22} /><span>{label}</span><small>The original file was removed. A placeholder is shown instead.</small></div>;
}

function VideoCover({ title }) {
  return <div className="video-cover" aria-label={`${title || 'Video'} cover`}><img src="/logo192.png" alt="" /><span><PlayCircle size={18} /> Video lesson</span></div>;
}

export default function Classroom({ token, user, studioInitially = false }) {
  const [communityView, setCommunityView] = useState(true);
  const [modules, setModules] = useState([]);
  const [feed, setFeed] = useState([]);
  const [moduleId, setModuleId] = useState(null);
  const [topic, setTopic] = useState(null);
  const [resources, setResources] = useState([]);
  const [comments, setComments] = useState([]);
  const [notes, setNotes] = useState([]);
  const [quiz, setQuiz] = useState([]);
  const [quizAnswers, setQuizAnswers] = useState({});
  const [quizResult, setQuizResult] = useState(null);
  const [quizQuestion, setQuizQuestion] = useState('');
  const [quizOptions, setQuizOptions] = useState(['', '', '', '']);
  const [quizCorrect, setQuizCorrect] = useState(0);
  const [discussionId, setDiscussionId] = useState(null);
  const [replyTo, setReplyTo] = useState(null);
  const [commentText, setCommentText] = useState('');
  const [error, setError] = useState('');
  const [moduleTitle, setModuleTitle] = useState('');
  const [subject, setSubject] = useState('');
  const [moduleBusy, setModuleBusy] = useState(false);
  const [expandedSubjects, setExpandedSubjects] = useState({});
  const [expandedModules, setExpandedModules] = useState({});
  const [outlineOpen, setOutlineOpen] = useState(false);
  const [postPanelOpen, setPostPanelOpen] = useState(false);
  const [feedPage, setFeedPage] = useState(0);
  const [topicTitle, setTopicTitle] = useState('');
  const [videoFile, setVideoFile] = useState(null);
  const [videoUrl, setVideoUrl] = useState('');
  const [videoMissing, setVideoMissing] = useState(false);
  const [speed, setSpeed] = useState(1);
  const videoRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const [resourceTitle, setResourceTitle] = useState('');
  const [resourceDescription, setResourceDescription] = useState('');
  const [resourceContent, setResourceContent] = useState('');
  const [resourceFile, setResourceFile] = useState(null);
  const [resourceInputKey, setResourceInputKey] = useState(0);
  const [noteTitle, setNoteTitle] = useState('');
  const [noteText, setNoteText] = useState('');
  const [editing, setEditing] = useState(null);
  const [bookmarks, setBookmarks] = useState(() => {
    try { return JSON.parse(localStorage.getItem(`oneplace_classroom_bookmarks_${user?.id}`) || '[]'); } catch { return []; }
  });
  const commentRef = useRef(null);
  const teacher = ['TEACHER', 'ADMIN'].includes(user?.role);

  const call = useCallback(async (path, options = {}) => {
    const response = await fetch(`/api/classroom${path}`, {
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...options.headers }
    });
    if (!response.ok) {
      throw new Error(classroomMessage(response.status));
    }
    if (response.status === 204) return null;
    return (response.headers.get('content-type') || '').includes('json') ? response.json() : response.blob();
  }, [token]);
  const run = async action => { setError(''); try { await action(); } catch (e) { setError(e instanceof TypeError ? 'Classroom service is still starting. Please wait while it reconnects.' : e.message); } };
  const refreshModules = useCallback(async () => {
    try {
      const [moduleItems, feedItems] = await Promise.all([call('/modules'), call('/feed')]);
      setModules(moduleItems); setFeed(feedItems);
    } catch (e) { setError(e.message); }
  }, [call]);
  useEffect(() => { if (token && ['STUDENT', 'TEACHER', 'ADMIN'].includes(user?.role)) refreshModules(); }, [token, user?.role, refreshModules]);
  useEffect(() => {
    if (!topic && feed.length) selectTopic(feed[0]);
    // The most recent lesson becomes the default full post; users can still browse the tree.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feed]);
  useEffect(() => { localStorage.setItem(`oneplace_classroom_bookmarks_${user?.id}`, JSON.stringify(bookmarks)); }, [bookmarks, user?.id]);
  useEffect(() => {
    setVideoUrl(''); setVideoMissing(false);
    if (!topic?.videoFile) return;
    let cancelled = false;
    call(`/topics/${topic.id}/video-ticket`)
      .then(result => { if (!cancelled) setVideoUrl(result.url); })
      .catch(() => { if (!cancelled) setVideoMissing(true); });
    return () => { cancelled = true; };
  }, [topic?.id, topic?.videoFile, call]);
  useEffect(() => {
    if (!topic) return;
    let cancelled = false;
    call(`/topics/${topic.id}/comments${discussionId == null ? '' : `?materialId=${discussionId}`}`)
      .then(items => { if (!cancelled) setComments(items); })
      .catch(e => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, [topic, discussionId, call]);

  useEffect(() => { if (videoRef.current) videoRef.current.playbackRate = speed; }, [speed, videoUrl]);
  const selectModule = id => { setTopic(null); setModuleId(id); setFeedPage(0); setExpandedModules(items => ({ ...items, [id]: !items[id] })); };
  const selectTopic = t => run(async () => {
    setTopic(t); setPostPanelOpen(false); setDiscussionId(null); setReplyTo(null); setComments([]);
    const [topicResources, topicQuiz, topicNotes] = await Promise.all([
      call(`/topics/${t.id}/resources`), call(`/topics/${t.id}/quiz`), user.role === 'STUDENT' ? call(`/topics/${t.id}/notes`) : Promise.resolve([])
    ]);
    setResources(topicResources); setQuiz(topicQuiz); setNotes(topicNotes); setQuizAnswers({}); setQuizResult(null); setEditing(null);
  });
  const toggleBookmark = topicId => setBookmarks(items => items.includes(topicId) ? items.filter(id => id !== topicId) : [...items, topicId]);
  const toggleLike = item => run(async () => {
    const result = await call(`/topics/${item.id}/like`, { method: 'POST' });
    const update = post => post.id === item.id ? { ...post, likeCount: result.likeCount, likedByCurrentUser: result.likedByCurrentUser } : post;
    setFeed(items => items.map(update));
    setTopic(current => current?.id === item.id ? update(current) : current);
  });
  const openComments = () => { setPostPanelOpen(true); setDiscussionId(null); requestAnimationFrame(() => commentRef.current?.focus()); };
  const openNotes = () => { setPostPanelOpen(true); requestAnimationFrame(() => document.getElementById('topic-notes')?.scrollIntoView({ behavior: 'smooth', block: 'start' })); };
  const saveModule = e => { e.preventDefault(); run(async () => {
    const title = moduleTitle.trim(); const subjectName = subject.trim();
    if (!title || !subjectName) { setError('Enter both a module title and a subject.'); return; }
    setModuleBusy(true);
    try {
      const created = await call('/modules', { method: 'POST', body: JSON.stringify({ title, subject: subjectName }) });
      setModuleTitle(''); setSubject(''); setModuleId(created.id); setTopic(null);
      setExpandedSubjects(items => ({ ...items, [subjectName]: true })); setExpandedModules(items => ({ ...items, [created.id]: true }));
      await refreshModules();
    } finally { setModuleBusy(false); }
  }); };
  const saveTopic = e => { e.preventDefault(); run(async () => {
    await call(`/modules/${moduleId}/topics`, { method: 'POST', body: JSON.stringify({ title: topicTitle, description: '' }) });
    setTopicTitle(''); await refreshModules();
  }); };
  const saveVideo = e => { e.preventDefault(); if (!videoFile) return; run(async () => {
    if (videoFile.size > MAX_VIDEO_BYTES) throw new Error(`Videos must be ${MAX_VIDEO_MB} MB or smaller.`);
    setUploading(true);
    try {
      const body = new FormData(); body.append('file', videoFile);
      const updated = await call(`/topics/${topic.id}/video`, { method: 'POST', body });
      setTopic(updated); setVideoFile(null); await refreshModules();
      recordActivity('Uploaded a classroom video', token);
    } finally { setUploading(false); }
  }); };
  const saveResource = e => { e.preventDefault(); run(async () => {
    setUploading(true);
    try {
      const body = new FormData(); body.append('title', resourceTitle); body.append('description', resourceDescription);
      body.append('content', resourceContent); if (resourceFile) body.append('file', resourceFile);
      await call(`/topics/${topic.id}/resources`, { method: 'POST', body });
      recordActivity('Added a classroom resource', token);
      setResources(await call(`/topics/${topic.id}/resources`));
      setResourceTitle(''); setResourceDescription(''); setResourceContent(''); setResourceFile(null); setResourceInputKey(k => k + 1);
    } finally { setUploading(false); }
  }); };
  const download = resource => run(async () => {
    try {
      const blob = await call(`/topics/${topic.id}/resources/${resource.id}/file`);
      const url = URL.createObjectURL(blob); const a = document.createElement('a');
      a.href = url; a.download = resource.originalName || resource.title; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { setResources(await call(`/topics/${topic.id}/resources`)); throw e; }
  });
  const post = e => { e.preventDefault(); run(async () => {
    await call(`/topics/${topic.id}/comments`, { method: 'POST', body: JSON.stringify({ text: commentText, parentId: replyTo?.id || null, materialId: discussionId }) });
    setCommentText(''); setReplyTo(null);
    setComments(await call(`/topics/${topic.id}/comments${discussionId == null ? '' : `?materialId=${discussionId}`}`));
  }); };
  const saveNote = e => { e.preventDefault(); run(async () => {
    await call(editing ? `/notes/${editing.id}` : `/topics/${topic.id}/notes`, { method: editing ? 'PUT' : 'POST', body: JSON.stringify({ title: noteTitle, text: noteText }) });
    if (!editing) recordActivity('Added a private note', token);
    setEditing(null); setNoteTitle(''); setNoteText(''); setNotes(await call(`/topics/${topic.id}/notes`));
  }); };
  const saveQuizQuestion = e => { e.preventDefault(); run(async () => {
    await call(`/topics/${topic.id}/quiz/questions`, { method: 'POST', body: JSON.stringify({ question: quizQuestion, options: quizOptions, correctAnswer: quizCorrect }) });
    setQuiz(await call(`/topics/${topic.id}/quiz`)); setQuizQuestion(''); setQuizOptions(['', '', '', '']); setQuizCorrect(0);
    recordActivity('Added a classroom quiz question', token);
  }); };
  const submitQuiz = e => { e.preventDefault(); run(async () => {
    setQuizResult(await call(`/topics/${topic.id}/quiz/submit`, { method: 'POST', body: JSON.stringify({ answers: quizAnswers }) }));
    recordActivity('Completed a classroom quiz', token);
  }); };
  const threads = useMemo(() => {
    const byId = new Map(comments.map(c => [c.id, c]));
    const groups = new Map();
    for (const c of comments) {
      if (!c.parentId || !byId.has(c.parentId)) { groups.set(c.id, { root: c, replies: [] }); }
    }
    for (const c of comments.filter(item => item.parentId && byId.has(item.parentId))) {
      let root = c, seen = new Set();
      while (root.parentId && byId.has(root.parentId) && !seen.has(root.id)) { seen.add(root.id); root = byId.get(root.parentId); }
      if (!groups.has(root.id)) groups.set(root.id, { root, replies: [] });
      groups.get(root.id).replies.push({ ...c, replyToName: c.replyToName || byId.get(c.parentId)?.authorName });
    }
    return [...groups.values()];
  }, [comments]);
  if (!user || !['STUDENT', 'TEACHER', 'ADMIN'].includes(user.role)) return <section className="section">Classroom access requires a student, teacher, or admin account.</section>;
  if (communityView) return <ClassroomFeed token={token} user={user} studioInitially={studioInitially} modules={modules} lessons={feed} savedLessons={feed.filter(item => bookmarks.includes(item.id))} onModule={id => { setModuleId(id); setCommunityView(false); }} onLesson={item => { setModuleId(item.moduleId); selectTopic(item); setCommunityView(false); }} />;
  const selectedResource = resources.find(r => r.id === discussionId);
  const replyButton = c => <button type="button" className="text-button" onClick={() => setReplyTo(c)}>Reply</button>;
  const editComment = c => user.role === 'ADMIN' && <button type="button" className="text-button" onClick={() => run(async () => { const text = window.prompt('Edit comment', c.text); if (!text?.trim()) return; await call(`/topics/${topic.id}/comments/${c.id}`, { method: 'PUT', body: JSON.stringify({ text }) }); setComments(await call(`/topics/${topic.id}/comments${discussionId === null ? '' : `?materialId=${discussionId}`}`)); })}>Edit comment</button>;
  const deleteComment = c => user.role === 'ADMIN' && <button type="button" className="text-button" onClick={() => run(async () => { await call(`/topics/${topic.id}/comments/${c.id}`, { method: 'DELETE' }); setComments(await call(`/topics/${topic.id}/comments${discussionId === null ? '' : `?materialId=${discussionId}`}`)); })}>Delete comment</button>;
  const allVisibleFeed = moduleId === null ? feed : feed.filter(item => item.moduleId === moduleId);
  const postsPerPage = 5;
  const pageCount = Math.max(1, Math.ceil(allVisibleFeed.length / postsPerPage));
  const currentPage = Math.min(feedPage, pageCount - 1);
  const visibleFeed = allVisibleFeed.slice(currentPage * postsPerPage, (currentPage + 1) * postsPerPage);
  const savedPosts = feed.filter(item => bookmarks.includes(item.id));
  return <section className="section page-section classroom">
    <div className="classroom-intro"><div><span className="eyebrow">LEARNING SPACE</span><h1>Classroom</h1><p className="muted">Modules, materials, videos, and discussion are shared with teachers and students. Your notes stay private to your account.</p><button type="button" className="text-button" onClick={() => setCommunityView(true)}>Back to recent posts</button></div><div className="classroom-mark" aria-hidden="true"><BookOpen size={34} /><Sparkles size={17} /></div></div>
    <button type="button" className={`outline-drawer-toggle ${outlineOpen ? 'open' : ''}`} onClick={() => setOutlineOpen(open => !open)} aria-expanded={outlineOpen} aria-controls="classroom-outline" aria-label={outlineOpen ? 'Close classroom categories' : 'Open classroom categories'}>{outlineOpen ? <PanelLeftClose size={21} /> : <MoreHorizontal size={25} />}</button>
    {outlineOpen && savedPosts.length > 0 && <section className="saved-resources" aria-label="Saved resources"><span className="eyebrow">COURSE OUTLINE</span><h2>Saved resources</h2><div>{savedPosts.map(item => <button type="button" key={item.id} onClick={() => { setModuleId(item.moduleId); selectTopic(item); setOutlineOpen(false); }}><Bookmark size={14} fill="currentColor" /> <span>{item.title}</span></button>)}</div></section>}
    {error && <p className="inline-error" role="alert">{error}</p>}
    {teacher && <form className="panel form module-creator" onSubmit={saveModule}><div><span className="eyebrow">TEACHER TOOLS</span><h2>Create a module</h2><p className="muted">Add the subject once, then organize lessons inside this module from the course tree.</p></div><input required placeholder="Module title" value={moduleTitle} onChange={e => setModuleTitle(e.target.value)} /><input required placeholder="Subject" value={subject} onChange={e => setSubject(e.target.value)} /><button className="primary" disabled={moduleBusy}>{moduleBusy ? 'Creating module…' : 'Create module'}</button></form>}
    <div className={`classroom-grid ${outlineOpen ? 'outline-open' : 'outline-hidden'}`}>{outlineOpen && <aside id="classroom-outline" className="classroom-sidebar"><div className="drawer-user"><span className="brand-mark"><BookOpen size={16} /></span><div><b>Classroom</b><small>Browse categories</small></div></div><div className="sidebar-heading"><span className="eyebrow">COURSE OUTLINE</span><h2>Subjects & modules</h2></div><button className={`sidebar-item sidebar-all ${moduleId === null ? 'active' : ''}`} onClick={() => { setModuleId(null); setTopic(null); setOutlineOpen(false); }}>All recent activity</button>{Object.entries(modules.reduce((groups, item) => { (groups[item.subject] ||= []).push(item); return groups; }, {})).map(([subjectName, subjectModules]) => <div className="sidebar-subject" key={subjectName}><button className="tree-toggle subject-toggle" onClick={() => setExpandedSubjects(items => ({ ...items, [subjectName]: !items[subjectName] }))}>{expandedSubjects[subjectName] ? <ChevronDown size={15} /> : <ChevronRight size={15} />}<span>{subjectName}</span><b>{subjectModules.length}</b></button>{expandedSubjects[subjectName] !== false && <div className="tree-branch">{subjectModules.map(m => { const moduleTopics = feed.filter(item => item.moduleId === m.id); const expanded = expandedModules[m.id] !== false; return <div className="sidebar-module" key={m.id}><button className={`tree-toggle module-toggle ${moduleId === m.id ? 'active' : ''}`} onClick={() => selectModule(m.id)}>{expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<span>{m.title}</span><b>{moduleTopics.length}</b></button>{expanded && <div className="tree-branch topic-branch">{moduleTopics.length ? moduleTopics.map(item => <button className={`tree-topic ${topic?.id === item.id ? 'active' : ''}`} key={item.id} onClick={() => { setModuleId(m.id); selectTopic(item); setOutlineOpen(false); }}>{item.title}</button>) : <p className="tree-empty">No lessons yet</p>}</div>}{teacher && <button className="text-button danger-action" onClick={() => run(async () => { await call(`/modules/${m.id}`, { method: 'DELETE' }); if (moduleId === m.id) { setModuleId(null); setTopic(null); } await refreshModules(); })}>Delete module</button>}</div>; })}</div>}</div>)}</aside>}
      <div className="classroom-feed"><div className="feed-heading"><div><span className="eyebrow">{moduleId === null ? 'RECENT ACTIVITY' : modules.find(item => item.id === moduleId)?.subject || 'SUBJECT'}</span><h2>{moduleId === null ? 'Learning feed' : modules.find(item => item.id === moduleId)?.title}</h2></div>{moduleId !== null && teacher && <form className="feed-topic-form form" onSubmit={saveTopic}><input required value={topicTitle} onChange={e => setTopicTitle(e.target.value)} placeholder="Add a topic" /><button className="primary small">Add topic</button></form>}</div>
      {visibleFeed.length ? <div className="feed-list">{visibleFeed.map(t => <article className={`feed-card ${topic?.id === t.id ? 'selected' : ''}`} key={t.id}><div className="feed-card-top"><span className="lesson-avatar"><BookOpen size={19} /></span><div className="feed-card-meta"><span>{t.subject}</span><span>•</span><span>{t.moduleTitle}</span></div></div><button className="feed-card-title" onClick={() => selectTopic(t)}><h3>{t.title}</h3></button>{t.description && <p>{t.description}</p>}<div className="feed-card-footer"><span>{t.videoFile ? <><PlayCircle size={15} /> Video lesson</> : 'Learning update'}</span><button className="text-button" onClick={() => selectTopic(t)}>{topic?.id === t.id ? 'Viewing' : 'Open post'}</button>{teacher && <><button className="text-button" onClick={() => run(async () => { const title = prompt('Topic name', t.title); if (title) { await call(`/topics/${t.id}`, { method: 'PUT', body: JSON.stringify({ title, description: t.description || '' }) }); await refreshModules(); if (topic?.id === t.id) setTopic({ ...t, title }); } })}>Rename</button><button className="text-button danger-action" onClick={() => run(async () => { await call(`/topics/${t.id}`, { method: 'DELETE' }); await refreshModules(); if (topic?.id === t.id) setTopic(null); })}>Delete</button></>}</div></article>)}</div> : <div className="empty classroom-empty"><span><Sparkles size={42} /></span><h2>No posts yet</h2><p>{moduleId === null ? 'Choose a subject from the left or wait for your teacher to publish a lesson.' : 'This category does not have any learning posts yet.'}</p></div>}
      {pageCount > 1 && <nav className="feed-pagination" aria-label="Learning feed pages"><button type="button" className="text-button" disabled={currentPage === 0} onClick={() => setFeedPage(page => Math.max(0, page - 1))}>Previous</button><span>Page {currentPage + 1} of {pageCount}</span><button type="button" className="text-button" disabled={currentPage === pageCount - 1} onClick={() => setFeedPage(page => Math.min(pageCount - 1, page + 1))}>Next</button></nav>}
      {topic && <article className={`feed-detail ${postPanelOpen ? 'post-panel-open' : 'post-panel-collapsed'}`}><div className="feed-card-meta"><span>{topic.subject || modules.find(item => item.id === topic.moduleId)?.subject || 'Learning post'}</span></div><h2>{topic.title}</h2>{topic.description && <p>{topic.description}</p>}
        <div className="feed-detail-actions"><button type="button" className={`feed-like ${topic.likedByCurrentUser ? 'liked' : ''}`} onClick={() => toggleLike(topic)} aria-pressed={!!topic.likedByCurrentUser}><Heart size={16} fill={topic.likedByCurrentUser ? 'currentColor' : 'none'} /> {topic.likedByCurrentUser ? 'Liked' : 'Like'} {topic.likeCount || 0}</button></div>
        <h3>Video</h3>{topic.videoFile && <><p>{topic.videoName || 'Video'}</p>{videoMissing ? <ResourcePlaceholder label="Video unavailable" /> : videoUrl ? <><div className="video-container"><video className="feed-video" ref={videoRef} controls playsInline src={videoUrl} onLoadedMetadata={e => { e.currentTarget.playbackRate = speed; }} /><div className="video-speed-overlay"><label>Speed <select value={speed} onChange={e => setSpeed(Number(e.target.value))}>{[.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.75].map(v => <option key={v} value={v}>{v}x</option>)}</select></label></div></div><div className="video-actions"><button type="button" onClick={openComments}><MessageCircle size={16} /> Comment</button><button type="button" onClick={openNotes}><NotebookPen size={16} /> Notes</button><button type="button" className={bookmarks.includes(topic.id) ? 'saved' : ''} onClick={() => toggleBookmark(topic.id)}><Bookmark size={16} fill={bookmarks.includes(topic.id) ? 'currentColor' : 'none'} /> {bookmarks.includes(topic.id) ? 'Saved' : 'Save'}</button></div></> : <><VideoCover title={topic.title} /><p className="video-loading">Preparing video…</p></>}</>}
        {teacher && <form className="form" onSubmit={saveVideo}><input type="file" accept="video/*" onChange={e => setVideoFile(e.target.files[0])} /><button className="primary small" disabled={uploading || !videoFile}>{uploading ? 'Uploading...' : 'Add/replace video'}</button>{topic.videoFile && <button type="button" className="text-button" onClick={() => run(async () => { await call(`/topics/${topic.id}/video`, { method: 'DELETE' }); const updated = { ...topic, videoFile: null, videoName: null }; setTopic(updated); await refreshModules(); })}>Delete video</button>}</form>}
        <h3>Topic quiz</h3>{teacher ? <><form className="form panel" onSubmit={saveQuizQuestion}><input required placeholder="Question" value={quizQuestion} onChange={e => setQuizQuestion(e.target.value)} />{quizOptions.map((option, index) => <label key={index}>Answer {index + 1}<input required value={option} onChange={e => setQuizOptions(options => options.map((value, i) => i === index ? e.target.value : value))} /></label>)}<label>Correct answer<select value={quizCorrect} onChange={e => setQuizCorrect(Number(e.target.value))}>{quizOptions.map((_, index) => <option key={index} value={index}>Answer {index + 1}</option>)}</select></label><button className="primary small">Add quiz question</button></form>{quiz.length ? <ol className="quiz-list">{quiz.map(question => <li key={question.id}><b>{question.question}</b><p>Correct answer: {question.options[question.correctAnswer]}</p></li>)}</ol> : <p>No quiz questions yet.</p>}</> : <>{quiz.length ? <form className="quiz-list form" onSubmit={submitQuiz}>{quiz.map(question => <fieldset key={question.id}><legend>{question.question}</legend>{question.options.map((option, index) => <label key={index}><input required type="radio" name={question.id} checked={quizAnswers[question.id] === index} onChange={() => setQuizAnswers(answers => ({ ...answers, [question.id]: index }))} /> {option}</label>)}</fieldset>)}<button className="primary small">Submit quiz</button></form> : <p>No quiz has been published for this topic.</p>}{quizResult && <p className="service-waiting">Quiz result: {quizResult.correct} of {quizResult.total} correct.</p>}</>}
        <h3>Study materials</h3>{resources.length === 0 && <p>No study materials yet.</p>}
        <div className="resource-list">{resources.map(r => <article className="resource-card" key={r.id}><h4>{r.title}</h4>{r.description && <p>{r.description}</p>}{r.originalName && <p>File: {r.originalName}</p>}{!r.available ? <ResourcePlaceholder label="Resource unavailable" /> : <>{r.content && <p className="resource-content">{r.content}</p>}{r.hasFile && <button type="button" className="text-button" onClick={() => download(r)}>Download file</button>}</>}
          <button type="button" className="text-button" onClick={() => { setDiscussionId(r.id); setReplyTo(null); setCommentText(''); }}>Discuss</button>{teacher && <button type="button" className="text-button" onClick={() => run(async () => { await call(`/topics/${topic.id}/resources/${r.id}`, { method: 'DELETE' }); setResources(await call(`/topics/${topic.id}/resources`)); if (discussionId === r.id) setDiscussionId(null); })}>Delete</button>}</article>)}</div>
        {teacher && <form className="form panel" onSubmit={saveResource}><h3>Add study material</h3><input required placeholder="Title" value={resourceTitle} onChange={e => setResourceTitle(e.target.value)} /><textarea placeholder="Description" value={resourceDescription} onChange={e => setResourceDescription(e.target.value)} /><textarea placeholder="Text notes (optional)" value={resourceContent} onChange={e => setResourceContent(e.target.value)} /><input key={resourceInputKey} type="file" accept=".pdf,.doc,.docx,.txt,image/*" onChange={e => setResourceFile(e.target.files[0])} /><button className="primary small" disabled={uploading || (!resourceFile && !resourceContent.trim())}>{uploading ? 'Uploading...' : 'Add material'}</button></form>}
        <button type="button" className="post-sidebar-toggle" onClick={() => setPostPanelOpen(open => !open)} aria-expanded={postPanelOpen}>{postPanelOpen ? 'Hide discussion & notes' : 'Open discussion & notes'}</button>{postPanelOpen && <aside className="post-sidebar" aria-label="Discussion and private notes"><div className="post-sidebar-heading"><span className="eyebrow">POST SPACE</span><h3>Public discussion: {selectedResource?.title || 'Video / topic'}</h3></div>{discussionId !== null && <button type="button" className="text-button" onClick={() => { setDiscussionId(null); setReplyTo(null); setCommentText(''); }}>View video discussion</button>}
        {threads.map(({ root, replies }) => (
          <article className="comment-thread" key={root.id}>
            <CommentHeader comment={root} user={user} />
            <p>{root.text}</p>
            {replyButton(root)}
            {editComment(root)}
            {deleteComment(root)}
            {replies.length > 0 && (
              <div className="comment-replies">
                {replies.map(c => (
                  <div className="comment-reply" key={c.id}>
                    <CommentHeader comment={c} user={user} />
                    <p>
                      {c.replyToName && <span className="mention">@{c.replyToName} </span>}
                      {c.text}
                    </p>
                    {replyButton(c)}
                    {editComment(c)}
                    {deleteComment(c)}
                  </div>
                ))}
              </div>
            )}
          </article>
        ))}
        <form className="form" onSubmit={post}>{replyTo && <p>Replying to <b className="mention">@{replyTo.authorName || 'Member'}</b> <button type="button" className="text-button" onClick={() => setReplyTo(null)}>Cancel</button></p>}<textarea ref={commentRef} required value={commentText} onChange={e => setCommentText(e.target.value)} placeholder="Write a public comment" /><button className="primary small">{replyTo ? 'Reply' : 'Comment'}</button></form>
        {user.role === 'STUDENT' && <section className="private-notes"><h3 id="topic-notes">My private topic notes</h3><form className="form" onSubmit={saveNote}><input required placeholder="Title" value={noteTitle} onChange={e => setNoteTitle(e.target.value)} /><textarea required placeholder="Private note about this topic, video, or material" value={noteText} onChange={e => setNoteText(e.target.value)} /><button className="primary small">{editing ? 'Update' : 'Add note'}</button></form>{notes.map(n => <article className="note-card" key={n.id}><b>{n.title}</b><p>{n.text}</p><button className="text-button" onClick={() => { setEditing(n); setNoteTitle(n.title); setNoteText(n.text); }}>Edit</button><button className="text-button" onClick={() => run(async () => { await call(`/notes/${n.id}`, { method: 'DELETE' }); setNotes(await call(`/topics/${topic.id}/notes`)); })}>Delete</button></article>)}</section>}</aside>}
      </article>}</div></div>
  </section>;
}
