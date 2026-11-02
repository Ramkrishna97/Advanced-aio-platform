import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import AdminEditDialog from './AdminEditDialog';

const emptyPost = { title: '', body: '', type: 'POST', resourceUrl: '', quizId: '', onBehalfOfUserId: '' };
const emptyProduct = { name: '', description: '', category: '', price: '', currency: 'INR', stock: 1, onBehalfOfUserId: '' };

export default function AdminPanel({ token, user, onLogout, onProductsChanged }) {
  const [users, setUsers] = useState([]);
  const [posts, setPosts] = useState([]);
  const [products, setProducts] = useState([]);
  const [quizzes, setQuizzes] = useState([]);
  const [materials, setMaterials] = useState([]);
  const [drafts, setDrafts] = useState([]);
  const [feedNotes, setFeedNotes] = useState([]);
  const [classNotes, setClassNotes] = useState([]);
  const [modules, setModules] = useState([]);
  const [topics, setTopics] = useState([]);
  const [carts, setCarts] = useState([]);
  const [orders, setOrders] = useState([]);
  const [deliveries, setDeliveries] = useState([]);
  const [account, setAccount] = useState({ name: '', email: '', password: '', role: 'STUDENT' });
  const [post, setPost] = useState(emptyPost);
  const [product, setProduct] = useState(emptyProduct);
  const [productImage, setProductImage] = useState(null);
  const [error, setError] = useState('');
  const [sectionErrors, setSectionErrors] = useState({});
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(null);
  const [collapsed, setCollapsed] = useState({});

  const reload = useCallback(async () => {
    const requests = [
      api('/api/auth/admin/users', {}, token),
      api('/api/products', {}, token),
      api('/api/quizzes/admin', {}, token),
      api('/api/classroom/materials', {}, token),
      api('/api/studio/admin/drafts', {}, token),
      api('/api/feed/admin/notes', {}, token),
      api('/api/classroom/admin/notes', {}, token),
      api('/api/classroom/modules', {}, token),
      api('/api/cart/admin', {}, token),
      api('/api/orders/admin', {}, token),
      api('/api/delivery/admin/orders', {}, token),
      (async () => { const allModules = await api('/api/classroom/modules', {}, token); const groups = await Promise.all(allModules.map(module => api(`/api/classroom/modules/${module.id}/topics`, {}, token))); return groups.flat(); })(),
      (async () => {
        const allPosts = [];
        let page = 0; let more = true;
        while (more) {
          const result = await api(`/api/feed?page=${page++}&size=50`, {}, token);
          allPosts.push(...result.items); more = result.hasNext;
        }
        return allPosts;
      })()
    ];
    const names = ['users', 'products', 'quizzes', 'materials', 'drafts', 'feedNotes', 'classNotes', 'modules', 'carts', 'orders', 'deliveries', 'topics', 'posts'];
    const setters = [setUsers, setProducts, setQuizzes, setMaterials, setDrafts, setFeedNotes, setClassNotes, setModules, setCarts, setOrders, setDeliveries, setTopics, setPosts];
    const results = await Promise.allSettled(requests);
    const failures = {};
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') setters[index](result.value);
      else failures[names[index]] = result.reason?.status ? `Could not load (${result.reason.status}).` : 'Could not load.';
    });
    setSectionErrors(failures);
  }, [token]);
  useEffect(() => { reload(); }, [reload]);

  const run = async (work, message) => {
    setBusy(true); setError(''); setNotice('');
    try { await work(); await reload(); setNotice(message); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const representative = (value, update) => <label>Publish on behalf of
    <select value={value} onChange={e => update(e.target.value)}><option value="">Generic admin content</option>
      {users.filter(u => u.role !== 'ADMIN').map(u => <option key={u.id} value={u.id}>{u.name} ({u.role})</option>)}
    </select>
  </label>;
  const sectionStatus = (name, items) => sectionErrors[name]
    ? <p className="inline-error" role="status">{sectionErrors[name]} <button type="button" className="text-button" onClick={reload}>Retry</button></p>
    : !items.length ? <p className="muted">No items to manage.</p> : null;
  const editButton = (kind, item) => <button type="button" className="text-button" disabled={busy} onClick={() => setEditing({ kind, item })}>Edit</button>;
  const sectionHeading = (label, key) => <h2><button type="button" className="admin-section-toggle" aria-expanded={!collapsed[key]} onClick={() => setCollapsed(current => ({ ...current, [key]: !current[key] }))}>{label}<span aria-hidden="true">{collapsed[key] ? '⌄' : '⌃'}</span></button></h2>;
  const savedEdit = async (kind, item) => {
    setEditing(null);
    if (kind === 'user' && item.id === user?.id) { onLogout(); return; }
    if (kind === 'product') await onProductsChanged();
    await reload(); setNotice('Changes saved.');
  };

  return <section className="section page-section admin-panel">
    <span className="eyebrow">ADMIN</span><h1>Manage OnePlace</h1>
    <p>Posts and listings created here show that an admin created them, including when published for another user.</p>
    {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="success-message" role="status">{notice}</p>}
    <div className="admin-grid">
      <section className="panel"><h2>Add user</h2><form className="form" onSubmit={e => { e.preventDefault(); run(async () => { await api('/api/auth/admin/users', { method: 'POST', body: JSON.stringify(account) }, token); setAccount({ name: '', email: '', password: '', role: 'STUDENT' }); }, 'User added.'); }}>
        <label>Name<input required value={account.name} onChange={e => setAccount({ ...account, name: e.target.value })} /></label>
        <label>Email<input required type="email" value={account.email} onChange={e => setAccount({ ...account, email: e.target.value })} /></label>
        <label>Password<input required minLength="8" type="password" value={account.password} onChange={e => setAccount({ ...account, password: e.target.value })} /></label>
        <label>Role<select value={account.role} onChange={e => setAccount({ ...account, role: e.target.value })}><option>STUDENT</option><option>TEACHER</option><option>BUYER</option><option>SELLER</option><option>ADMIN</option></select></label>
        <button className="primary small" disabled={busy}>Add user</button></form></section>
      <section className="panel"><h2>Create post</h2><form className="form" onSubmit={e => { e.preventDefault(); run(async () => { await api('/api/feed', { method: 'POST', body: JSON.stringify({ ...post, onBehalfOfUserId: post.onBehalfOfUserId ? Number(post.onBehalfOfUserId) : null }) }, token); setPost(emptyPost); }, 'Post published.'); }}>
        {representative(post.onBehalfOfUserId, value => setPost({ ...post, onBehalfOfUserId: value }))}
        <label>Title<input required value={post.title} onChange={e => setPost({ ...post, title: e.target.value })} /></label>
        <label>Type<select value={post.type} onChange={e => setPost({ ...post, type: e.target.value })}><option value="POST">Post</option><option value="RESOURCE">Resource</option><option value="IMAGE">Image</option><option value="VIDEO">Video</option><option value="QUIZ">Quiz</option></select></label>
        <label>Body<textarea value={post.body} onChange={e => setPost({ ...post, body: e.target.value })} /></label>
        {post.type !== 'POST' && <label>Resource URL<input value={post.resourceUrl} onChange={e => setPost({ ...post, resourceUrl: e.target.value })} /></label>}
        {post.type === 'QUIZ' && <label>Quiz ID<input value={post.quizId} onChange={e => setPost({ ...post, quizId: e.target.value })} /></label>}
        <button className="primary small" disabled={busy}>Publish post</button></form></section>
      <section className="panel"><h2>Add product</h2><form className="form" onSubmit={e => { e.preventDefault(); run(async () => { const created = await api('/api/products', { method: 'POST', body: JSON.stringify({ ...product, price: Number(product.price), stock: Number(product.stock), onBehalfOfUserId: product.onBehalfOfUserId ? Number(product.onBehalfOfUserId) : null }) }, token); if (productImage) { const body = new FormData(); body.append('file', productImage); await api(`/api/products/${created.id}/image`, { method: 'POST', body }, token); } setProduct(emptyProduct); setProductImage(null); await onProductsChanged(); }, 'Product added.'); }}>
        {representative(product.onBehalfOfUserId, value => setProduct({ ...product, onBehalfOfUserId: value }))}
        <label>Name<input required value={product.name} onChange={e => setProduct({ ...product, name: e.target.value })} /></label>
        <label>Description<textarea required value={product.description} onChange={e => setProduct({ ...product, description: e.target.value })} /></label>
        <label>Category<input required value={product.category} onChange={e => setProduct({ ...product, category: e.target.value })} /></label>
        <label>Price (INR)<input required type="number" min="0.01" step="0.01" value={product.price} onChange={e => setProduct({ ...product, price: e.target.value })} /></label>
        <label>Stock<input required type="number" min="1" value={product.stock} onChange={e => setProduct({ ...product, stock: e.target.value })} /></label>
        <label>Image (optional)<input type="file" accept="image/*" onChange={e => setProductImage(e.target.files?.[0] || null)} /></label>
        <button className="primary small" disabled={busy}>Add product</button></form></section>
    </div>
    <div className="admin-grid">
      <section className="panel">{sectionHeading('Users', 'users')}{sectionStatus('users', users)}{users.map(u => <div className="admin-row" key={u.id}><span><b>{u.name}</b><small>{u.email} · {u.role}</small></span>{editButton('user', u)}{u.id !== user?.id && <button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Permanently delete ${u.name}? They will need a new account to sign in again.`)) run(() => api(`/api/auth/admin/users/${u.id}`, { method: 'DELETE' }, token), 'User deleted.'); }}>Delete</button>}</div>)}</section>
      <section className="panel">{sectionHeading('Recent posts', 'posts')}{sectionStatus('posts', posts)}{posts.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b><small>{item.authorName}</small></span>{editButton('post', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete “${item.title}”?`)) run(() => api(`/api/feed/${item.id}`, { method: 'DELETE' }, token), 'Post deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Products', 'products')}{sectionStatus('products', products)}{products.map(item => <div className="admin-row" key={item.id}><span><b>{item.name}</b><small>{item.createdByAdmin ? 'Created by admin · ' : ''}Seller #{item.sellerId}</small></span>{editButton('product', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete “${item.name}”?`)) run(async () => { await api(`/api/products/${item.id}`, { method: 'DELETE' }, token); await onProductsChanged(); }, 'Product deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Quizzes', 'quizzes')}{sectionStatus('quizzes', quizzes)}{quizzes.map(item => <div className="admin-row" key={item.id}><span><b>{item.name}</b><small>{item.module} · {item.subject}</small></span>{editButton('quiz', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Permanently delete quiz “${item.name}”, its posts, and attempts?`)) run(async () => { for (const linked of posts.filter(p => p.quizId === String(item.id))) await api(`/api/feed/${linked.id}`, { method: 'DELETE' }, token); await api(`/api/quizzes/${item.id}`, { method: 'DELETE' }, token); }, 'Quiz deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Classroom materials', 'materials')}{sectionStatus('materials', materials)}{materials.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b></span>{editButton('material', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete material “${item.title}”?`)) run(() => api(`/api/classroom/materials/${item.id}`, { method: 'DELETE' }, token), 'Material deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Studio drafts', 'drafts')}{sectionStatus('drafts', drafts)}{drafts.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b><small>Author #{item.teacherId}</small></span>{editButton('draft', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete draft “${item.title}”?`)) run(() => api(`/api/studio/admin/drafts/${item.id}`, { method: 'DELETE' }, token), 'Draft deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Private feed notes', 'feedNotes')}{sectionStatus('feedNotes', feedNotes)}{feedNotes.map(item => <div className="admin-row" key={item.id}><span><b>{item.text}</b><small>User #{item.userId} · Post #{item.postId}</small></span>{editButton('feedNote', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm('Delete this note?')) run(() => api(`/api/feed/admin/notes/${item.id}`, { method: 'DELETE' }, token), 'Note deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Classroom notes', 'classNotes')}{sectionStatus('classNotes', classNotes)}{classNotes.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b><small>Student #{item.studentId} · Topic #{item.topicId}</small></span>{editButton('classNote', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm('Delete this note?')) run(() => api(`/api/classroom/notes/${item.id}`, { method: 'DELETE' }, token), 'Note deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Classroom modules', 'modules')}{sectionStatus('modules', modules)}{modules.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b><small>{item.subject}</small></span>{editButton('module', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete module “${item.title}” and its topics?`)) run(() => api(`/api/classroom/modules/${item.id}`, { method: 'DELETE' }, token), 'Module deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Classroom topics', 'topics')}{sectionStatus('topics', topics)}{topics.map(item => <div className="admin-row" key={item.id}><span><b>{item.title}</b><small>Module #{item.moduleId}</small></span>{editButton('topic', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm(`Delete topic “${item.title}”?`)) run(() => api(`/api/classroom/topics/${item.id}`, { method: 'DELETE' }, token), 'Topic deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Shopping carts', 'carts')}{sectionStatus('carts', carts)}{carts.map(item => <div className="admin-row" key={item.id}><span><b>Product #{item.productId}</b><small>User #{item.userId} · Quantity {item.quantity}</small></span>{editButton('cart', item)}<button type="button" className="text-button" disabled={busy} onClick={() => { if (window.confirm('Delete this cart item?')) run(() => api(`/api/cart/admin/${item.id}`, { method: 'DELETE' }, token), 'Cart item deleted.'); }}>Delete</button></div>)}</section>
      <section className="panel">{sectionHeading('Orders', 'orders')}{sectionStatus('orders', orders)}{orders.map(item => <div className="admin-row" key={item.id}><span><b>Order #{item.id}</b><small>User #{item.userId} · {item.deliveryStatus}</small></span>{editButton('order', item)}</div>)}</section>
      <section className="panel">{sectionHeading('Delivery records', 'deliveries')}{sectionStatus('deliveries', deliveries)}{deliveries.map(item => <div className="admin-row" key={item.id}><span><b>Order #{item.orderId}</b><small>User #{item.userId} · {item.status}</small></span>{editButton('delivery', item)}</div>)}</section>    </div>
    {editing && <AdminEditDialog key={`${editing.kind}-${editing.item.id}`} kind={editing.kind} item={editing.item} users={users} token={token} onClose={() => setEditing(null)} onSaved={savedEdit} />}
  </section>;
}
