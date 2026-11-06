import { useState } from 'react';
import { api } from './api';

const roles = ['STUDENT', 'TEACHER', 'BUYER', 'SELLER', 'ADMIN'];
const postTypes = ['POST', 'RESOURCE', 'IMAGE', 'VIDEO', 'QUIZ'];
const questionTemplate = () => ({ question: '', options: ['', '', '', ''], correctAnswer: 0 });

export default function AdminEditDialog({ kind, item, users, token, onClose, onSaved }) {
  const [form, setForm] = useState(() => ({ ...item, password: '', attribution: 'keep', questions: item.questions?.map(q => ({ ...q, options: [...q.options] })), itemsJson: item.items ? JSON.stringify(item.items, null, 2) : '[]' }));
  const [images, setImages] = useState([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const change = key => event => setForm(current => ({ ...current, [key]: event.target.value }));
  const field = (label, key, required = false) => <label>{label}<input required={required} value={form[key] ?? ''} onChange={change(key)} /></label>;
  const area = (label, key) => <label>{label}<textarea value={form[key] ?? ''} onChange={change(key)} /></label>;
  const select = (label, key, values) => <label>{label}<select value={form[key] ?? ''} onChange={change(key)}>{values.map(value => <option key={value} value={value}>{value}</option>)}</select></label>;

  const save = async event => {
    event.preventDefault(); setBusy(true); setError('');
    try {
      let path, body;
      switch (kind) {
        case 'user':
          path = `/api/auth/admin/users/${item.id}`;
          body = { name: form.name, email: form.email, role: form.role, seller: !!form.seller, password: form.password || null };
          break;
        case 'post':
          path = `/api/feed/${item.id}`;
          body = { title: form.title, body: form.body, type: form.type, resourceUrl: form.resourceUrl, quizId: form.quizId,
            asAdmin: form.attribution === 'admin', onBehalfOfUserId: form.attribution.startsWith('user:') ? Number(form.attribution.slice(5)) : null };
          break;
        case 'product':
          path = `/api/products/${item.id}`;
          body = { name: form.name, description: form.description, category: form.category, price: Number(form.price), currency: 'INR', stock: Number(form.stock),
            asAdmin: form.attribution === 'admin', onBehalfOfUserId: form.attribution.startsWith('user:') ? Number(form.attribution.slice(5)) : null };
          break;
        case 'quiz':
          path = `/api/quizzes/${item.id}`;
          body = { name: form.name, module: form.module, subject: form.subject, topic: form.topic, timeMinutes: Number(form.timeMinutes), questions: form.questions };
          break;
        case 'material': path = `/api/classroom/materials/${item.id}`; body = { title: form.title, description: form.description, content: form.content }; break;
        case 'draft': path = `/api/studio/admin/drafts/${item.id}`; body = { title: form.title, body: form.body, type: form.type, resourceUrl: form.resourceUrl, quizId: form.quizId }; break;
        case 'feedNote': path = `/api/feed/admin/notes/${item.id}`; body = { text: form.text }; break;
        case 'classNote': path = `/api/classroom/notes/${item.id}`; body = { title: form.title, text: form.text }; break;
        case 'module': path = `/api/classroom/modules/${item.id}`; body = { title: form.title, subject: form.subject }; break;
        case 'topic': path = `/api/classroom/topics/${item.id}`; body = { title: form.title, description: form.description }; break;
        case 'cart': path = `/api/cart/admin/${item.id}`; body = { userId: Number(form.userId), productId: Number(form.productId), quantity: Number(form.quantity) }; break;
        case 'order': path = `/api/orders/admin/${item.id}`; body = { userId: Number(form.userId), total: Number(form.total), invoiceNumber: form.invoiceNumber, deliveryStatus: form.deliveryStatus, items: JSON.parse(form.itemsJson) }; break;
        case 'delivery': path = `/api/delivery/admin/orders/${item.id}`; body = { userId: Number(form.userId), paymentStatus: form.paymentStatus, status: form.status, acknowledged: !!form.acknowledged }; break;
        default: throw new Error('Unsupported edit');
      }
      await api(path, { method: 'PUT', body: JSON.stringify(body) }, token);
      if (kind === 'product' && images.length) { const data = new FormData(); images.forEach(image => data.append('files', image)); await api(`/api/products/${item.id}/images`, { method: 'POST', body: data, timeout: null }, token); }
      await onSaved(kind, item);
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };

  const attribution = <label>Attribution / owner<select value={form.attribution} onChange={change('attribution')}>
    <option value="keep">Keep current</option><option value="admin">Generic admin</option>
    {users.map(user => <option key={user.id} value={`user:${user.id}`}>{user.name} ({user.role})</option>)}
  </select></label>;
  const updateQuestion = (index, patch) => setForm(current => ({ ...current, questions: current.questions.map((q, i) => i === index ? { ...q, ...patch } : q) }));

  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="auth-modal admin-edit-dialog" role="dialog" aria-modal="true" aria-label={`Edit ${kind}`}>
      <button type="button" className="modal-close" onClick={onClose} aria-label="Close">x</button>
      <h2>Edit {kind}</h2><form className="form" onSubmit={save}>
        {kind === 'user' && <>{field('Name', 'name', true)}<label>Email<input required type="email" value={form.email} onChange={change('email')} /></label>{select('Role', 'role', roles)}<label><input type="checkbox" checked={!!form.seller} onChange={event => setForm(current => ({ ...current, seller: event.target.checked }))} /> Seller access</label><label>New password (optional)<input type="password" minLength="8" value={form.password} onChange={change('password')} /></label></>}
        {kind === 'post' && <>{field('Title', 'title', true)}{select('Type', 'type', postTypes)}{area('Body', 'body')}{field('Resource URL', 'resourceUrl')}{field('Quiz ID', 'quizId')}{attribution}</>}
        {kind === 'product' && <>{field('Name', 'name', true)}{area('Description', 'description')}{field('Category', 'category', true)}<label>Price (INR)<input required type="number" min="0.01" step="0.01" value={form.price ?? ''} onChange={change('price')} /></label><label>Stock<input required type="number" min="0" value={form.stock ?? ''} onChange={change('stock')} /></label>{attribution}<label>Replace images (optional, up to 3)<input type="file" accept="image/*" multiple onChange={event => { const selected = Array.from(event.target.files || []); if (selected.length > 3) { setImages([]); setError('Choose no more than 3 product images.'); event.target.value = ''; } else setImages(selected); }} /></label></>}
        {kind === 'quiz' && <>{field('Name', 'name', true)}{field('Module', 'module')}{field('Subject', 'subject')}{field('Topic', 'topic')}<label>Time (minutes)<input required type="number" min="1" value={form.timeMinutes ?? ''} onChange={change('timeMinutes')} /></label>{form.questions?.map((q, index) => <fieldset key={q.id || index}><legend>Question {index + 1}</legend><label>Question<input required value={q.question} onChange={event => updateQuestion(index, { question: event.target.value })} /></label>{q.options.map((option, optionIndex) => <label key={optionIndex}>Option {optionIndex + 1}<input required value={option} onChange={event => updateQuestion(index, { options: q.options.map((value, i) => i === optionIndex ? event.target.value : value) })} /></label>)}<label>Correct option<select value={q.correctAnswer} onChange={event => updateQuestion(index, { correctAnswer: Number(event.target.value) })}>{q.options.map((_, i) => <option key={i} value={i}>Option {i + 1}</option>)}</select></label>{form.questions.length > 1 && <button type="button" className="text-button" onClick={() => setForm(current => ({ ...current, questions: current.questions.filter((_, i) => i !== index) }))}>Remove question</button>}</fieldset>)}<button type="button" className="text-button" onClick={() => setForm(current => ({ ...current, questions: [...current.questions, questionTemplate()] }))}>Add question</button></>}
        {kind === 'material' && <>{field('Title', 'title', true)}{area('Description', 'description')}{area('Content', 'content')}</>}
        {kind === 'draft' && <>{field('Title', 'title', true)}{select('Type', 'type', postTypes)}{area('Body', 'body')}{field('Resource URL', 'resourceUrl')}{field('Quiz ID', 'quizId')}</>}
        {kind === 'feedNote' && area('Text', 'text')}
        {kind === 'classNote' && <>{field('Title', 'title', true)}{area('Text', 'text')}</>}
        {kind === 'module' && <>{field('Title', 'title', true)}{field('Subject', 'subject', true)}</>}
        {kind === 'topic' && <>{field('Title', 'title', true)}{area('Description', 'description')}</>}
        {kind === 'cart' && <>{field('User ID', 'userId', true)}{field('Product ID', 'productId', true)}<label>Quantity<input required type="number" min="1" value={form.quantity ?? ''} onChange={change('quantity')} /></label></>}
        {kind === 'order' && <>{field('User ID', 'userId', true)}<label>Total<input required type="number" min="0" step="0.01" value={form.total ?? ''} onChange={change('total')} /></label>{field('Invoice number', 'invoiceNumber', true)}{field('Delivery status', 'deliveryStatus', true)}{area('Items (JSON)', 'itemsJson')}</>}
        {kind === 'delivery' && <>{field('User ID', 'userId', true)}{field('Payment status', 'paymentStatus', true)}{field('Status', 'status', true)}<label><input type="checkbox" checked={!!form.acknowledged} onChange={event => setForm(current => ({ ...current, acknowledged: event.target.checked }))} /> Acknowledged</label></>}
        {error && <p className="inline-error" role="alert">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Saving...' : 'Save changes'}</button>
      </form>
    </div>
  </div>;
}
