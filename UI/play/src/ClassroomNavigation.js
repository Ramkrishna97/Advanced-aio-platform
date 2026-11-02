import { useEffect, useRef, useState } from 'react';
import { BookOpen, Bookmark, ChevronDown, ChevronRight, PanelLeftClose, PanelLeftOpen, X } from 'lucide-react';

export default function ClassroomNavigation({ modules = [], lessons = [], savedLessons = [], savedPosts = [], onLesson, onModule, onFeed, onSavedPost }) {
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState({});
  const toggleRef = useRef(null);
  const drawerRef = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    drawerRef.current?.focus();
    const onKeyDown = event => {
      if (event.key === 'Escape') {
        setOpen(false);
        toggleRef.current?.focus();
      }
      if (event.key !== 'Tab' || !drawerRef.current) return;
      const controls = [...drawerRef.current.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled)')];
      if (!controls.length) return;
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === drawerRef.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => { document.body.style.overflow = previousOverflow; document.removeEventListener('keydown', onKeyDown); };
  }, [open]);
  const close = () => { setOpen(false); toggleRef.current?.focus(); };
  const groups = modules.reduce((result, module) => {
    (result[module.subject || 'Other'] ||= []).push(module);
    return result;
  }, {});
  return <>
    <button ref={toggleRef} type="button" className="outline-drawer-toggle" onClick={() => setOpen(value => !value)} aria-expanded={open} aria-controls="classroom-navigation" aria-label={open ? 'Close classroom navigation' : 'Open classroom navigation'}>{open ? <PanelLeftClose size={22} /> : <PanelLeftOpen size={22} />}</button>
    {open && <><button type="button" className="classroom-nav-backdrop" onClick={close} aria-label="Close classroom navigation" tabIndex={-1} /><aside ref={drawerRef} id="classroom-navigation" className="classroom-sidebar community-sidebar" aria-label="Classroom navigation" tabIndex={-1}><div className="drawer-user"><span className="brand-mark"><BookOpen size={16} /></span><div><b>Classroom</b><small>Subjects, modules and saved posts</small></div><button type="button" className="drawer-close" onClick={close} aria-label="Close navigation"><X size={18} /></button></div><button type="button" className="sidebar-item sidebar-all" onClick={() => { onFeed(); close(); }}>All recent posts</button><div className="sidebar-heading"><span className="eyebrow">COURSE OUTLINE</span><h2>Subjects & modules</h2></div>{Object.entries(groups).map(([subject, entries]) => <div key={subject} className="sidebar-subject"><button type="button" className="tree-toggle subject-toggle" aria-expanded={expanded[subject] !== false} onClick={() => setExpanded(value => ({ ...value, [subject]: value[subject] === false }))}>{expanded[subject] === false ? <ChevronRight size={15} /> : <ChevronDown size={15} />}<span>{subject}</span><b>{entries.length}</b></button>{expanded[subject] !== false && entries.map(module => <div key={module.id} className="tree-branch"><button type="button" className="tree-toggle module-toggle" onClick={() => { onModule(module.id); close(); }}>{module.title}</button>{lessons.filter(item => item.moduleId === module.id).map(item => <button type="button" className="tree-topic" key={item.id} onClick={() => { onLesson(item); close(); }}>{item.title}</button>)}</div>)}</div>)}{!modules.length && <p className="tree-empty">No modules yet</p>}<div className="sidebar-heading"><span className="eyebrow">BOOKMARKS</span><h2>Saved posts</h2></div>{savedLessons.map(item => <button type="button" className="tree-topic" key={`lesson-${item.id}`} onClick={() => { onLesson(item); close(); }}><Bookmark size={13} /> {item.title}</button>)}{savedPosts.map(item => <button type="button" className="tree-topic" key={`post-${item.id}`} onClick={() => { onSavedPost(item.id); close(); }}><Bookmark size={13} /> {item.title}</button>)}{!savedLessons.length && !savedPosts.length && <p className="tree-empty">No saved posts yet</p>}</aside></>}
  </>;
}
