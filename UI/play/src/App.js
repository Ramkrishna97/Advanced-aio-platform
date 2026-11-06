import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { ArrowRight, BookOpen, LayoutDashboard, LogOut, Moon, ShoppingBag, ShoppingCart, Sparkles, Store, ReceiptText, Sun } from 'lucide-react';
import { api, recordActivity } from './api';
import './App.css';
import Classroom from './Classroom';
import AdminPanel from './AdminPanel';

const TOKEN = 'oneplace_token';
const PROFILE = 'oneplace_profile';
const GUEST = 'oneplace_guest_cart';
const THEME = 'oneplace_theme';
const MAX_PRODUCT_IMAGES = 3;
const localCart = () => { try { return JSON.parse(localStorage.getItem(GUEST) || '[]'); } catch { return []; } };
const localProfile = () => { try { return localStorage.getItem(TOKEN) ? JSON.parse(localStorage.getItem(PROFILE) || 'null') : null; } catch { return null; } };
const money = (amount) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(amount || 0);
const icon = (category) => ({ Stationery: 'N', Home: 'H', Accessories: 'A' })[category] || 'S';
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const clamp = (value, minimum, maximum) => Math.max(minimum, Math.min(maximum, value));
const uploadProductImages = async (productId, images, token) => {
  if (!images.length) return;
  const body = new FormData();
  images.forEach(image => body.append('files', image));
  await api(`/api/products/${productId}/images`, { method: 'POST', body, timeout: null }, token);
};

function App() {
  const [page, setPage] = useState(window.history.state?.page || 'dashboard');
  const [auth, setAuth] = useState(window.history.state?.auth || '');
  const [token, setToken] = useState(localStorage.getItem(TOKEN) || '');
  const [user, setUser] = useState(localProfile);
  const [products, setProducts] = useState([]);
  const [cart, setCart] = useState([]);
  const [guest, setGuest] = useState(localCart);
  const [orders, setOrders] = useState([]);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const [serviceErrors, setServiceErrors] = useState({});
  const sessionRef = useRef(token); sessionRef.current = token;
  const accountRef = useRef(user?.id); accountRef.current = user?.id;
  const photoRequest = useRef(0);
  const profileRevision = useRef(0);
  const setServiceError = useCallback((name, message) => setServiceErrors(old => ({ ...old, [name]: message })), []);
  const [catalogState, setCatalogState] = useState('loading');
  const [busy, setBusy] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [photoCropFile, setPhotoCropFile] = useState(null);
  const [photoPreview, setPhotoPreview] = useState('');
  const [photoStatus, setPhotoStatus] = useState('');
  const [sellerActivationPending, setSellerActivationPending] = useState(false);
  const [darkMode, setDarkMode] = useState(() => localStorage.getItem(THEME) === 'dark');

  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
    localStorage.setItem(THEME, darkMode ? 'dark' : 'light');
  }, [darkMode]);

  const navigate = useCallback((next, mode = '') => {
    window.history.pushState({ page: next, auth: mode }, '', `#${next}`);
    setPage(next); setAuth(mode); setNotice('');
  }, []);

  useEffect(() => {
    window.history.replaceState({ page, auth: '' }, '', `#${page}`);
    const back = (event) => { setPage(event.state?.page || 'dashboard'); setAuth(event.state?.auth || ''); };
    window.addEventListener('popstate', back);
    return () => window.removeEventListener('popstate', back);
    // The initial history entry is set once; navigation is handled above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadProducts = useCallback(async () => {
    try { setProducts(await api('/api/products')); setCatalogState('ready'); }
    catch { setCatalogState((state) => state === 'ready' ? 'reconnecting' : 'waiting'); }
  }, []);
  const loadCart = useCallback(async (key) => {
    if (!key) return;
    try { const data = await api('/api/cart', {}, key); if (sessionRef.current === key) { setCart(data); setServiceError('cart', ''); } }
    catch (error) { if (sessionRef.current === key) setServiceError('cart', error.message); }
  }, [setServiceError]);
  const loadOrders = useCallback(async (key) => {
    if (!key) return;
    try {
      const data = await api('/api/orders', {}, key);
      const enriched = await Promise.all(data.map(async (order) => {
        try {
          const delivery = await api(`/api/delivery/orders/${order.id}`, {}, key);
          return { ...order, deliveryStatus: delivery.status, paymentStatus: delivery.paymentStatus, deliveryHistory: delivery.history };
        } catch { return order; }
      }));
      if (sessionRef.current === key) { setOrders(enriched); setServiceError('orders', ''); }
    }
    catch (error) { if (sessionRef.current === key) setServiceError('orders', error.message); }
  }, [setServiceError]);
  useEffect(() => {
    let stopped = false; let timer;
    const refresh = async () => { await loadProducts(); if (!stopped) timer = setTimeout(refresh, 15000); };
    refresh(); return () => { stopped = true; clearTimeout(timer); };
  }, [loadProducts]);
  useEffect(() => {
    if (!token) return undefined;
    let stopped = false; let timer;
    const reconnect = async () => {
      try {
        const revision = profileRevision.current;
        const profile = await api('/api/auth/me', {}, token);
        if (!stopped && revision === profileRevision.current) { setUser(profile); localStorage.setItem(PROFILE, JSON.stringify(profile)); setServiceError('identity', ''); }
      } catch (error) {
        if (stopped) return;
        if (error.status === 401) { localStorage.removeItem(TOKEN); localStorage.removeItem(PROFILE); setToken(''); setUser(null); return; }
        setServiceError('identity', error.message);
        timer = setTimeout(reconnect, 5000);
      }
    };
    reconnect(); loadCart(token); loadOrders(token);
    return () => { stopped = true; clearTimeout(timer); };
  }, [token, loadCart, loadOrders, setServiceError]);
  useEffect(() => {
    if (!token || (!serviceErrors.cart && !serviceErrors.orders)) return undefined;
    const timer = setTimeout(() => {
      if (serviceErrors.cart) loadCart(token);
      if (serviceErrors.orders) loadOrders(token);
    }, 5000);
    return () => clearTimeout(timer);
  }, [token, serviceErrors, loadCart, loadOrders]);
  useEffect(() => () => { if (photoPreview) URL.revokeObjectURL(photoPreview); }, [photoPreview]);
  useEffect(() => { localStorage.setItem(GUEST, JSON.stringify(guest)); }, [guest]);

  const mergeGuest = async (key) => {
    const pending = localCart();
    if (!pending.length) return api('/api/cart', {}, key);
    const merged = await api('/api/cart/merge', { method: 'POST', body: JSON.stringify(pending) }, key);
    setGuest([]); localStorage.removeItem(GUEST);
    return merged;
  };

  const onAuth = async (result) => {
    localStorage.setItem(TOKEN, result.token);
    localStorage.setItem(PROFILE, JSON.stringify(result.user));
    setToken(result.token); setUser(result.user);
    window.history.replaceState({ page, auth: '' }, '', `#${page}`);
    setAuth('');
    try { setCart(await mergeGuest(result.token)); setNotice(`Welcome, ${result.user.name}. Your cart is ready.`); }
    catch { setNotice('Signed in. Your saved cart will synchronize when the cart service is ready.'); }
    loadOrders(result.token);
  };

  const logout = () => {
    localStorage.removeItem(TOKEN);
    localStorage.removeItem(PROFILE);
    sessionRef.current = ''; photoRequest.current++;
    setToken(''); setUser(null); setCart([]); setOrders([]); setServiceErrors({}); setPhotoPreview(''); setPhotoStatus(''); setProfileOpen(false); setPhotoCropFile(null);
    setNotice('You signed out.');
  };

  const updateProfile = (profile) => {
    profileRevision.current++;
    const saved = localProfile();
    if (saved?.id === profile.id) {
      profile = { ...profile, seller: profile.seller || saved.seller, hasPhoto: profile.hasPhoto || saved.hasPhoto,
        roles: [...new Set([...(saved.roles || []), ...(profile.roles || [])])] };
    }
    localStorage.setItem(PROFILE, JSON.stringify(profile)); setUser(profile);
  };
  const saveProfile = async (details) => {
    const result = await api('/api/auth/me', { method: 'PUT', body: JSON.stringify(details) }, token);
    localStorage.setItem(TOKEN, result.token);
    sessionRef.current = result.token;
    setToken(result.token);
    updateProfile(result.user);
    return result.user;
  };
  const enableSeller = async () => {
    if (!token) { navigate('dashboard', 'login'); return false; }
    if (user?.seller || sellerActivationPending) return false;
    const key = token;
    setSellerActivationPending(true);
    setNotice('Enabling your seller workspace…');
    const finish = (result) => {
      if (sessionRef.current !== key) return false;
      localStorage.setItem(TOKEN, result.token); setToken(result.token); updateProfile(result.user);
      setNotice('Seller workspace is ready. You can now list products while keeping your current roles.');
      return true;
    };
    try {
      const result = await api('/api/auth/seller', { method: 'POST', timeout: null }, key);
      return finish(result);
    } catch (error) {
      if (sessionRef.current !== key) return false;
      try {
        const latest = await api('/api/auth/me', {}, key);
        if (latest.seller) return finish(await api('/api/auth/seller', { method: 'POST', timeout: null }, key));
      } catch { /* Preserve the first useful error when verification is unavailable. */ }
      setNotice(`Seller workspace could not be enabled. ${error.message}`);
      return false;
    } finally { setSellerActivationPending(false); }
  };
  const startPhotoUpload = async (photo) => {
    const request = ++photoRequest.current;
    const account = user.id;
    const key = token;
    setPhotoPreview(URL.createObjectURL(photo)); setPhotoStatus('Saving photo…');
    try {
      const body = new FormData(); body.append('file', photo);
      const profile = await api('/api/auth/me/photo', { method: 'POST', body, timeout: null }, key);
      if (request !== photoRequest.current || accountRef.current !== account) return;
      updateProfile(profile); setPhotoStatus('Photo saved.');
    } catch (error) {
      if (request !== photoRequest.current || accountRef.current !== account) return;
      try {
        const profile = await api('/api/auth/me', {}, key);
        if (profile.photoUpdatedAt && profile.photoUpdatedAt !== user.photoUpdatedAt) {
          updateProfile(profile); setPhotoStatus('Photo saved.'); return;
        }
      } catch { /* Keep the original upload error if verification is unavailable. */ }
      setPhotoPreview(''); setPhotoStatus(`Photo could not be saved. ${error.message}`);
    }
  };

  const add = async (product) => {
    const line = { productId: product.id, quantity: 1 };
    if (token) {
      try { setCart(await api('/api/cart/items', { method: 'POST', body: JSON.stringify(line) }, token)); setNotice(`${product.name} added.`); return; }
      catch { setNotice('Cart service unavailable. Item saved on this device.'); }
    } else setNotice(`${product.name} added to your guest cart.`);
    setGuest((items) => {
      const old = items.find((item) => item.productId === product.id);
      return old ? items.map((item) => item.productId === product.id ? { ...item, quantity: item.quantity + 1 } : item) : [...items, line];
    });
  };

  const remove = async (id) => {
    if (guest.some((item) => item.productId === id)) { setGuest((items) => items.filter((item) => item.productId !== id)); return; }
    try { setCart(await api(`/api/cart/items/${id}`, { method: 'DELETE' }, token)); }
    catch (error) { setNotice(error.message); }
  };

  const checkout = async () => {
    if (!token) { navigate('cart', 'login'); return; }
    setBusy(true);
    try {
      setCart(await mergeGuest(token));
      const payment = await api('/api/orders/prepare-payment', { method: 'POST' }, token);
      loadProducts();
      setNotice('Inventory is reserved. Please review the payment confirmation.');
      await wait(2000);
      if (!window.confirm('Sure to pay?')) {
        await api(`/api/orders/payments/${payment.id}/cancel`, { method: 'POST' }, token);
        setNotice('Payment cancelled. Your cart is still available.');
        return;
      }
      setNotice('Payment is being processed...');
      await wait(5000);
      const order = await api(`/api/orders/payments/${payment.id}/complete`, { method: 'POST' }, token);
      recordActivity('Placed an order', token);
      setCart([]); setOrders((old) => [order, ...old]);
      navigate('orders'); setNotice(`Payment successful. Order #${order.id} has been placed.`);
      loadProducts();
    } catch (error) { setNotice(error.message); }
    finally { setBusy(false); }
  };

  const lines = useMemo(() => {
    const totals = new Map();
    [...cart, ...guest].forEach((item) => totals.set(item.productId, (totals.get(item.productId) || 0) + item.quantity));
    return [...totals].map(([productId, quantity]) => ({ productId, quantity, product: products.find((p) => p.id === productId) }));
  }, [cart, guest, products]);
  const total = lines.reduce((sum, line) => sum + (line.product?.price || 0) * line.quantity, 0);
  const navItems = [
    { id: 'dashboard', label: 'Home', Icon: LayoutDashboard },
    { id: 'shop', label: 'Marketplace', Icon: ShoppingBag },
    ...(['STUDENT', 'TEACHER', 'ADMIN'].includes(user?.role) ? [{ id: 'classroom', label: 'Classroom', Icon: BookOpen }] : []),
    ...(['TEACHER', 'ADMIN'].includes(user?.role) ? [{ id: 'studio', label: 'Studio editor', Icon: BookOpen }] : []),
    ...(user?.role === 'ADMIN' ? [{ id: 'admin', label: 'Admin', Icon: LayoutDashboard }] : []),
    ...(user?.seller ? [{ id: 'sell', label: 'Seller studio', Icon: Store }] : []),
  ];

  return <div className="app-shell">
    <header className="topbar shadow-sm bg-white/90 backdrop-blur-xl"><button className="brand" onClick={() => navigate('dashboard')}><span className="brand-mark" aria-hidden="true"><Sparkles size={17} strokeWidth={2.8} /></span> OnePlace</button>
      <nav aria-label="Main navigation">{navItems.map(({ id, label, Icon }) => <button key={id} className={page === id ? 'active' : ''} onClick={() => { navigate(id); if (id === 'orders') loadOrders(token); }}><Icon size={16} aria-hidden="true" /><span>{label}</span></button>)}</nav>
      <div className="header-actions"><button className="theme-toggle" type="button" onClick={() => setDarkMode(value => !value)} aria-label={`Switch to ${darkMode ? 'light' : 'dark'} mode`} title={`Switch to ${darkMode ? 'light' : 'dark'} mode`}>{darkMode ? <Sun size={17} /> : <Moon size={17} />}</button><button className="cart-link" onClick={() => navigate('cart')}><ShoppingCart size={17} aria-hidden="true" /> Cart <span className="badge rounded-pill">{lines.reduce((n, line) => n + line.quantity, 0)}</span></button>{user && <button className="text-button" onClick={() => { navigate('orders'); loadOrders(token); }}><ReceiptText size={16} /> Orders</button>}{user ? <><HeaderAvatar user={user} token={token} photoPreview={photoPreview} onClick={() => setProfileOpen(true)} /><button className="text-button logout-button" onClick={logout}><LogOut size={16} /> <span>Log out</span></button></> : <button className="primary small" onClick={() => navigate(page, 'login')}>Sign in <ArrowRight size={16} /></button>}</div>
    </header>
    <main className="relative overflow-hidden">{Object.entries(serviceErrors).filter(([, message]) => message).map(([service, message]) => <p className="service-waiting" role="status" key={service}>{service}: {message}</p>)}{notice && <div role="status" className="notice">{notice}<button aria-label="Dismiss" onClick={() => setNotice('')}>{String.fromCharCode(215)}</button></div>}
      <AnimatePresence mode="wait"><motion.div className="page-transition min-h-[68vh]" key={page} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.24, ease: 'easeOut' }}>
      {page === 'dashboard' && <><section className="hero"><div className="hero-copy"><span className="eyebrow">YOUR EVERYDAY HUB</span><h1>Everything you need,<br /><em>all in one place.</em></h1><p>Browse the marketplace, discover useful finds, and manage your shopping from one simple dashboard.</p><button className="primary" onClick={() => navigate('shop')}>Explore the shop <ArrowRight size={18} /></button></div><div className="hero-art" aria-hidden="true"><div className="hero-orb orb-one" /><div className="hero-orb orb-two" /><Sparkles size={110} /></div></section><section className="section"><span className="eyebrow">QUICK ACCESS</span><h2>Your dashboard</h2><div className="feature-grid"><Feature Icon={ShoppingBag} title="Marketplace" description="Explore products from independent sellers." onClick={() => navigate('shop')} /><Feature Icon={ShoppingCart} title="Your cart" description="Keep your favorites together, even as a guest." onClick={() => navigate('cart')} /><Feature Icon={Store} title="Seller studio" description="Register as a seller and list your products." onClick={() => navigate('sell')} /></div></section><Products title="Featured finds" items={products.slice(0, 3)} onAdd={add} state={catalogState} retry={loadProducts} onViewAll={() => navigate('shop')} /></>}
      {page === 'shop' && <section className="section page-section"><div className="section-title"><div><span className="eyebrow">MARKETPLACE</span><h1>Find something good.</h1><p>Everyone can browse. Add to cart now and sign in when you're ready to check out.</p></div><input className="search" placeholder="Search products" aria-label="Search products" value={search} onChange={(event) => setSearch(event.target.value)} /></div><Products items={products.filter((p) => `${p.name} ${p.category}`.toLowerCase().includes(search.toLowerCase()))} onAdd={add} state={catalogState} retry={loadProducts} /></section>}
      {page === 'cart' && <section className="section page-section narrow"><span className="eyebrow">YOUR PICKS</span><h1>Shopping cart</h1>{lines.length ? <><div className="cart-list">{lines.map((line) => <div className="cart-row" key={line.productId}><div className="product-thumb small-thumb">{icon(line.product?.category)}</div><div><h3>{line.product?.name || `Product #${line.productId}`}</h3><p>Quantity: {line.quantity}</p></div><strong>{line.product ? money(line.product.price * line.quantity) : 'Price unavailable'}</strong><button className="text-button" onClick={() => remove(line.productId)}>Remove</button></div>)}</div><div className="cart-summary"><div><span>Estimated total</span><strong>{money(total)}</strong></div><p>Price and stock are confirmed at checkout.</p><button className="primary" disabled={busy} onClick={checkout}>{busy ? 'Checking out...' : token ? 'Place order' : 'Sign in to check out'}</button></div></> : <Empty title="Your cart is empty" description="Items you add appear here, even before you sign in." action={() => navigate('shop')} label="Browse products" />}</section>}
      {page === 'classroom' && <Classroom token={token} user={user} />}
      {page === 'studio' && <Classroom token={token} user={user} studioInitially />}
      {page === 'admin' && (user?.role === 'ADMIN' ? <AdminPanel token={token} user={user} onLogout={logout} onProductsChanged={loadProducts} /> : <section className="section">Admin access required.</section>)}
      {page === 'sell' && <section className="section page-section narrow"><span className="eyebrow">SELLER STUDIO</span><h1>Manage what you sell.</h1><p>Check product inventory, edit your listings, and follow each order from its generated bill through delivery.</p>{!user ? <Empty title="Sign in to start selling" description="Create an account or sign in first." action={() => navigate('sell', 'login')} label="Sign in" /> : !user.seller ? <Empty title="Enable your seller workspace" description="You can sell and keep buying with this same account." action={enableSeller} label={sellerActivationPending ? 'Enabling seller workspace...' : 'Enable selling'} /> : <SellerStudio token={token} onChanged={loadProducts} />}</section>}
      {page === 'orders' && <section className="section page-section narrow"><span className="eyebrow">PURCHASE HISTORY</span><h1>Your orders</h1>{!user ? <Empty title="Sign in to see orders" description="Your orders are linked to your account." action={() => navigate('orders', 'login')} label="Sign in" /> : orders.length ? <div className="order-list">{orders.map((order) => <article className="order-card" key={order.id}><div><h3>Order #{order.id}</h3><span>{new Date(order.createdAt).toLocaleString()}</span></div><strong>{money(order.total)}</strong><p>{order.items.map((item) => `${item.quantity} x ${item.name}`).join(', ')}</p></article>)}</div> : <Empty title="No orders yet" description="Orders will appear after checkout." action={() => navigate('shop')} label="Explore products" />}</section>}
      </motion.div></AnimatePresence>
    </main><footer>OnePlace  |  Independent services demo</footer>
    {auth && <AuthDialog mode={auth} onClose={() => window.history.back()} onMode={(mode) => { window.history.replaceState({ page, auth: mode }, '', `#${page}`); setAuth(mode); }} onSuccess={onAuth} />}
    {profileOpen && user && <ProfileDialog user={user} token={token} onClose={() => setProfileOpen(false)} onSaveProfile={saveProfile} onEnableSeller={enableSeller} sellerActivationPending={sellerActivationPending} onPickPhoto={setPhotoCropFile} photoPreview={photoPreview} photoStatus={photoStatus} />}
    {photoCropFile && <PhotoCropDialog file={photoCropFile} onClose={() => setPhotoCropFile(null)} onApply={(photo) => { setPhotoCropFile(null); startPhotoUpload(photo); }} />}
  </div>;
}

function Feature({ Icon, title, description, onClick }) {
  const [opensLeft, setOpensLeft] = useState(false);
  const reactToPointer = event => {
    const bounds = event.currentTarget.getBoundingClientRect();
    event.currentTarget.style.setProperty('--pointer-x', `${((event.clientX - bounds.left) / bounds.width) * 100}%`);
    event.currentTarget.style.setProperty('--pointer-y', `${((event.clientY - bounds.top) / bounds.height) * 100}%`);
  };
  return <button className={`feature ${opensLeft ? 'opens-left' : 'opens-right'}`} onClick={onClick} onMouseEnter={event => setOpensLeft(event.currentTarget.getBoundingClientRect().left > window.innerWidth / 2)} onMouseMove={reactToPointer} onMouseLeave={event => event.currentTarget.style.removeProperty('--pointer-x')}><span className="feature-icon"><Icon size={30} strokeWidth={2.1} /></span><span className="feature-copy"><h3>{title}</h3><p>{description}</p><strong>Open <ArrowRight size={15} /></strong></span></button>;
}
function Empty({ title, description, action, label }) { return <div className="empty"><span><Sparkles size={42} /></span><h2>{title}</h2><p>{description}</p><button className="primary" onClick={action}>{label} <ArrowRight size={16} /></button></div>; }
function HeaderAvatar({ user, token, photoPreview, onClick }) {
  const [photoUrl, setPhotoUrl] = useState('');
  useEffect(() => {
    if (!user.hasPhoto) { setPhotoUrl(''); return undefined; }
    let url; let cancelled = false;
    const controller = new AbortController();
    api('/api/auth/me/photo', { responseType: 'blob', signal: controller.signal, headers: { 'Cache-Control': 'no-store' } }, token)
      .then(blob => { if (!cancelled) { url = URL.createObjectURL(blob); setPhotoUrl(url); } })
      .catch(() => { if (!cancelled) setPhotoUrl(''); });
    return () => { cancelled = true; controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [token, user.hasPhoto, user.photoUpdatedAt]);
  const roleClass = user.role === 'TEACHER' ? 'teacher' : user.role === 'STUDENT' ? 'student' : 'member';
  const image = photoPreview || photoUrl;
  return <button className={`header-avatar ${roleClass}`} onClick={onClick} aria-label="Open your profile" title="Open your profile">{image ? <img src={image} alt="" /> : <span>{user.name?.slice(0, 1).toUpperCase()}</span>}</button>;
}
function ProductSlideshow({ product }) {
  const files = product.imageFiles?.length ? product.imageFiles : product.imageFile ? [product.imageFile] : [];
  const [active, setActive] = useState(0);
  useEffect(() => { setActive(0); }, [product.id, files.length]);
  useEffect(() => {
    if (files.length < 2) return undefined;
    const timer = setInterval(() => setActive(current => (current + 1) % files.length), 1000);
    return () => clearInterval(timer);
  }, [files.length]);
  const source = files.length ? `/api/products/${product.id}/images/${active}?v=${encodeURIComponent(files[active])}` : `/api/products/${product.id}/image?v=default`;
  return <div className="product-slideshow"><img key={`${product.id}-${active}`} className="product-thumb product-slide" src={source} alt={`${product.name}${files.length > 1 ? ` image ${active + 1}` : ''}`} loading="lazy" />{files.length > 1 && <div className="slide-dots" aria-label={`${product.name} images`}>{files.map((file, index) => <button key={file} type="button" className={index === active ? 'active' : ''} aria-label={`Show image ${index + 1}`} onClick={() => setActive(index)} />)}</div>}</div>;
}

function Products({ title, items, onAdd, state, retry, onViewAll }) { const waiting = state !== 'ready'; return <section className="section products-section">{title && <div className="section-title"><h2>{title}</h2><button className="text-button" onClick={onViewAll}>View all -></button></div>}{waiting && <p className="service-waiting" role="status">{state === 'reconnecting' ? 'Marketplace service is reconnecting. Your listings will refresh automatically.' : 'Loading marketplace service… Product listings will appear automatically.'} <button className="text-button" onClick={retry}>Refresh now</button></p>}<div className="product-grid">{items.map((p) => <article className="product-card" key={p.id}><ProductSlideshow product={p} /><div className="product-info"><span className="category">{p.category}</span><h3>{p.name}</h3><p>{p.description}</p>{p.createdByAdmin && <p className="muted">Created by admin</p>}<div className="product-bottom"><strong>{money(p.price)}</strong><button className="primary small" disabled={p.stock < 1} onClick={() => onAdd(p)}>{p.stock < 1 ? 'Sold out' : 'Add to cart'}</button></div></div></article>)}</div>{!waiting && !items.length && <p className="muted">No products found.</p>}</section>; }

function SellerForm({ token, onCreated }) {
  const [form, setForm] = useState({ name: '', description: '', category: '', price: '', currency: 'INR', stock: '1' });
  const [images, setImages] = useState([]);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const field = (name) => (event) => setForm({ ...form, [name]: event.target.value });
  const pickImages = event => { const selected = Array.from(event.target.files || []); if (selected.length > MAX_PRODUCT_IMAGES) { setImages([]); setError('Choose no more than 3 product images.'); event.target.value = ''; return; } setImages(selected); setError(''); };
  const submit = async (event) => { event.preventDefault(); setBusy(true); setError(''); try { const product = await api('/api/products', { method: 'POST', body: JSON.stringify({ ...form, price: Number(form.price), stock: Number(form.stock) }) }, token); await uploadProductImages(product.id, images, token); recordActivity('Listed a product', token); onCreated(); } catch (err) { setError(err.message); } finally { setBusy(false); } };
  return <form className="panel form" onSubmit={submit}><h2>List a product</h2><label>Product name<input required value={form.name} onChange={field('name')} /></label><label>Description<textarea required value={form.description} onChange={field('description')} /></label><label>Category<input required value={form.category} onChange={field('category')} /></label><label>Product images (optional, select up to 3 together)<input type="file" accept="image/*" multiple onChange={pickImages} /></label><p className="muted">{images.length ? `${images.length} image${images.length === 1 ? '' : 's'} selected. ` : ''}Each image is automatically resized to 20 KB or less.</p><div className="form-row"><label>Listing price<input required type="number" min="0.01" step="0.01" value={form.price} onChange={field('price')} /></label><label>Listing currency<select value={form.currency} onChange={field('currency')}><option value="INR">Indian Rupee (INR)</option><option value="USD">US Dollar (USD)</option><option value="EUR">Euro (EUR)</option><option value="GBP">British Pound (GBP)</option></select></label><label>Stock<input required type="number" min="1" value={form.stock} onChange={field('stock')} /></label></div><p className="muted">Marketplace prices are shown in INR; other currencies are converted when you publish.</p>{error && <p className="inline-error">{error}</p>}<button className="primary" disabled={busy}>{busy ? 'Publishing...' : 'Publish product'}</button></form>;
}

function SellerStudio({ token, onChanged }) {
  const [products, setProducts] = useState([]); const [orders, setOrders] = useState([]); const [editing, setEditing] = useState(null); const [error, setError] = useState('');
  const load = useCallback(async () => { try { const [mine, sales] = await Promise.all([api('/api/products/mine', {}, token), api('/api/seller/orders', {}, token)]); setProducts(mine); setOrders(sales); setError(''); } catch (err) { setError(err.message); } }, [token]);
  useEffect(() => { load(); const timer = setInterval(load, 15000); return () => clearInterval(timer); }, [load]);
  const updated = async () => { await load(); onChanged(); };
  return <><SellerForm token={token} onCreated={updated} />
    <section className="panel"><div className="section-title"><div><h2>Product status</h2><p>Your live listing details and available inventory.</p></div><button className="text-button" onClick={load}>Refresh</button></div>{error && <p className="inline-error">{error}</p>}{products.length ? <div className="seller-product-list">{products.map(product => <article className="seller-product" key={product.id}><img className="seller-thumb" src={`/api/products/${product.id}/image?v=${encodeURIComponent(product.imageFile || 'default')}`} alt="" /><div><h3>{product.name}</h3><p>{product.description}</p><p><b>Available:</b> {product.stock} &nbsp; <b>Current price:</b> {money(product.price)}</p><button className="text-button" onClick={() => setEditing(product)}>Edit details, price, or inventory</button></div><div className="seller-order-summary"><b>{orders.filter(order => order.items.some(item => item.productId === product.id)).length}</b><span>orders</span></div></article>)}</div> : <p className="muted">You have not listed a product yet.</p>}</section>
    <section className="panel"><h2>Order and delivery status</h2><p>Each stage advances automatically every 15 seconds.</p>{orders.length ? <div className="order-list">{orders.map(order => <article className="order-card" key={order.id}><div><h3>Order #{order.id}</h3><span>{new Date(order.createdAt).toLocaleString()}</span></div><strong>{money(order.total)}</strong><p><b>Bill:</b> {order.invoiceNumber || 'Generated for this order'} &nbsp; <b>Delivery:</b> {order.deliveryStatus || 'Ordered'}</p><p>{order.items.map(item => `${item.quantity} x ${item.name}`).join(', ')}</p></article>)}</div> : <p className="muted">Orders containing your products will appear here.</p>}</section>
    {editing && <ProductEditor product={editing} token={token} onClose={() => setEditing(null)} onSaved={async () => { setEditing(null); await updated(); }} />}
  </>;
}

function ProductEditor({ product, token, onClose, onSaved }) {
  const [form, setForm] = useState({ name: product.name, description: product.description, category: product.category, price: product.price, currency: 'INR', stock: product.stock }); const [images, setImages] = useState([]); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const field = name => event => setForm({ ...form, [name]: event.target.value });
  const pickImages = event => { const selected = Array.from(event.target.files || []); if (selected.length > MAX_PRODUCT_IMAGES) { setImages([]); setError('Choose no more than 3 product images.'); event.target.value = ''; return; } setImages(selected); setError(''); };
  const submit = async event => { event.preventDefault(); setBusy(true); setError(''); try { await api(`/api/products/${product.id}`, { method: 'PUT', body: JSON.stringify({ ...form, price: Number(form.price), stock: Number(form.stock) }) }, token); await uploadProductImages(product.id, images, token); recordActivity('Updated a product', token); await onSaved(); } catch (err) { setError(err.message); } finally { setBusy(false); } };
  return <div className="modal-backdrop"><div className="auth-modal seller-editor" role="dialog" aria-modal="true" aria-label="Edit product"><button className="modal-close" onClick={onClose} aria-label="Close">x</button><h2>Edit {product.name}</h2><form className="form" onSubmit={submit}><label>Product name<input required value={form.name} onChange={field('name')} /></label><label>Description<textarea required value={form.description} onChange={field('description')} /></label><label>Category<input required value={form.category} onChange={field('category')} /></label><label>Replace product images (optional, up to 3)<input type="file" accept="image/*" multiple onChange={pickImages} /></label><div className="form-row"><label>Marketplace price (INR)<input required type="number" min="0.01" step="0.01" value={form.price} onChange={field('price')} /></label><label>Available inventory<input required type="number" min="0" value={form.stock} onChange={field('stock')} /></label></div>{error && <p className="inline-error">{error}</p>}<button className="primary" disabled={busy}>{busy ? 'Saving...' : 'Save product status'}</button></form></div></div>;
}

function ProfileDialog({ user, token, onClose, onSaveProfile, onEnableSeller, sellerActivationPending, onPickPhoto, photoPreview, photoStatus }) {
  const [activities, setActivities] = useState([]); const [photoUrl, setPhotoUrl] = useState(''); const [error, setError] = useState(''); const [sellerMessage, setSellerMessage] = useState('');
  const [profile, setProfile] = useState({ name: user.name || '', email: user.email || '', phone: user.phone || '', bio: user.bio || '', currentPassword: '', newPassword: '' });
  const [saving, setSaving] = useState(false); const [savedMessage, setSavedMessage] = useState('');
  const load = useCallback(async () => { try {
    const items = await api('/api/auth/activities', {}, token);
    const summaries = [...items.reduce((groups, item) => {
      const current = groups.get(item.action) || { ...item, count: 0 };
      current.count += 1; groups.set(item.action, current); return groups;
    }, new Map()).values()].map(item => ({ ...item, action: `${item.action} (${item.count})` }));
    setActivities(summaries); setError('');
  } catch { setError('Recent activity is temporarily unavailable. Your profile changes are still saved.'); } }, [token]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!user.hasPhoto) { setPhotoUrl(''); return undefined; }
    let url; let cancelled = false;
    const controller = new AbortController();
    api('/api/auth/me/photo', { responseType: 'blob', signal: controller.signal, headers: { 'Cache-Control': 'no-store' } }, token).then(blob => { if (!cancelled) { url = URL.createObjectURL(blob); setPhotoUrl(url); } }).catch(() => { if (!cancelled) setPhotoUrl(''); });
    return () => { cancelled = true; controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [token, user.hasPhoto, user.photoUpdatedAt]);
  const enableSeller = async () => { setSellerMessage('Enabling seller workspace…'); const enabled = await onEnableSeller(); setSellerMessage(enabled ? 'Seller workspace is ready.' : 'Seller workspace was not enabled. Please retry.'); };
  const save = async event => { event.preventDefault(); setSaving(true); setError(''); setSavedMessage(''); try { const updated = await onSaveProfile(profile); setProfile(current => ({ ...current, name: updated.name, email: updated.email, phone: updated.phone || '', bio: updated.bio || '', currentPassword: '', newPassword: '' })); setSavedMessage('Profile details updated.'); } catch (failure) { setError(failure.message); } finally { setSaving(false); } };
  const field = name => event => setProfile(current => ({ ...current, [name]: event.target.value }));
  const roles = user.roles?.length ? user.roles : [...new Set([user.role || 'STUDENT', 'BUYER', ...(user.seller ? ['SELLER'] : [])])];
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}><div className="auth-modal profile-modal" role="dialog" aria-modal="true" aria-label="Your profile"><button className="modal-close" onClick={onClose} aria-label="Close">x</button><span className="eyebrow">YOUR PROFILE</span><div className="profile-header"><div className="profile-photo">{photoPreview || photoUrl ? <img src={photoPreview || photoUrl} alt={`${user.name}'s profile`} /> : user.name?.slice(0, 1).toUpperCase()}</div><div><h2>{user.name}</h2><p>{user.email}</p></div></div><label className="photo-upload">Change profile photo<input type="file" accept="image/*" onChange={event => { const file = event.target.files?.[0]; if (file) onPickPhoto(file); event.target.value = ''; }} /></label><p className="photo-help">Choose the crop, then the optimized photo saves in the background.</p>{photoStatus && <p className="service-waiting">{photoStatus}</p>}<form className="form profile-details" onSubmit={save}><h3>Edit account details</h3><label>Name<input required value={profile.name} onChange={field('name')} /></label><label>Email<input required type="email" value={profile.email} onChange={field('email')} /></label><label>Phone<input type="tel" maxLength="30" value={profile.phone} onChange={field('phone')} /></label><label>About you<textarea maxLength="500" value={profile.bio} onChange={field('bio')} /></label><h3>Change password</h3><p className="muted">Leave both password fields empty to keep your current password.</p><label>Current password<input type="password" value={profile.currentPassword} onChange={field('currentPassword')} /></label><label>New password<input type="password" minLength={profile.newPassword ? 8 : undefined} value={profile.newPassword} onChange={field('newPassword')} /></label>{error && <p className="inline-error">{error}</p>}{savedMessage && <p className="success-message">{savedMessage}</p>}<button className="primary small" disabled={saving}>{saving ? 'Saving…' : 'Save profile'}</button></form><h3>Roles</h3><div className="role-list">{roles.map(role => <span key={role}>{role}</span>)}</div>{!user.seller && <><button className="primary small" disabled={sellerActivationPending} onClick={enableSeller}>{sellerActivationPending ? 'Enabling seller workspace...' : 'Enable seller workspace'}</button>{sellerMessage && <p className="service-waiting">{sellerMessage}</p>}</>}<div className="profile-activity"><div className="section-title"><h3>Recorded activity</h3><button className="text-button" onClick={load}>Refresh</button></div>{activities.length ? <ul>{activities.map(activity => <li key={activity.id}><span>{activity.action}</span><time>{new Date(activity.createdAt).toLocaleString()}</time></li>)}</ul> : <p className="muted">Your actions will appear here.</p>}</div></div></div>;
}

export function PhotoCropDialog({ file, onClose, onApply }) {
  const [image, setImage] = useState(null); const [zoom, setZoom] = useState(1); const [x, setX] = useState(0); const [y, setY] = useState(0); const [error, setError] = useState(''); const canvasRef = useRef(null);
  useEffect(() => { let cancelled = false; createImageBitmap(file).then(bitmap => { if (cancelled) bitmap.close(); else setImage(bitmap); }).catch(() => setError('This image could not be prepared.')); return () => { cancelled = true; }; }, [file]);
  const drag = useRef(null);
  useEffect(() => () => image?.close(), [image]);
  const movePhoto = event => {
    if (!drag.current || !image) return;
    const edge = Math.min(image.width, image.height) / zoom;
    const scale = edge / event.currentTarget.getBoundingClientRect().width;
    const dx = event.clientX - drag.current.x; const dy = event.clientY - drag.current.y;
    drag.current = { x: event.clientX, y: event.clientY };
    if (image.width > edge) setX(value => clamp(value - dx * scale * 200 / (image.width - edge), -100, 100));
    if (image.height > edge) setY(value => clamp(value - dy * scale * 200 / (image.height - edge), -100, 100));
  };
  const draw = useCallback(() => {
    if (!image || !canvasRef.current) return;
    const canvas = canvasRef.current; const context = canvas.getContext('2d'); const edge = Math.min(image.width, image.height) / zoom;
    const maxX = (image.width - edge) / 2; const maxY = (image.height - edge) / 2;
    const sourceX = clamp((image.width - edge) / 2 + (x / 100) * maxX, 0, image.width - edge);
    const sourceY = clamp((image.height - edge) / 2 + (y / 100) * maxY, 0, image.height - edge);
    context.fillStyle = '#fff'; context.fillRect(0, 0, 256, 256); context.drawImage(image, sourceX, sourceY, edge, edge, 0, 0, 256, 256);
  }, [image, zoom, x, y]);
  useEffect(() => { draw(); }, [draw]);
  const apply = () => { canvasRef.current.toBlob(blob => { if (blob) onApply(new File([blob], 'profile-photo.jpg', { type: 'image/jpeg' })); else setError('The crop could not be saved.'); }, 'image/jpeg', 0.86); };
  return <div className="modal-backdrop"><div className="auth-modal crop-modal" role="dialog" aria-modal="true" aria-label="Crop profile photo"><button className="modal-close" onClick={onClose} aria-label="Close">x</button><span className="eyebrow">PROFILE PHOTO</span><h2>Crop your photo</h2><canvas className="crop-preview" ref={canvasRef} width="256" height="256" onPointerDown={event => { event.currentTarget.setPointerCapture(event.pointerId); drag.current = { x: event.clientX, y: event.clientY }; }} onPointerMove={movePhoto} onPointerUp={() => { drag.current = null; }} onPointerCancel={() => { drag.current = null; }} />{error && <p className="inline-error">{error}</p>}<label>Zoom<input type="range" min="1" max="3" step="0.05" value={zoom} onChange={event => setZoom(Number(event.target.value))} /></label><label>Horizontal position<input type="range" min="-100" max="100" value={x} onChange={event => setX(Number(event.target.value))} /></label><label>Vertical position<input type="range" min="-100" max="100" value={y} onChange={event => setY(Number(event.target.value))} /></label><button className="primary" disabled={!image || !!error} onClick={apply}>Use this photo</button></div></div>;
}

function AuthDialog({ mode, onClose, onMode, onSuccess }) {
  const [role, setRole] = useState('STUDENT'); const [name, setName] = useState(''); const [email, setEmail] = useState(''); const [password, setPassword] = useState('');
  const [resetToken, setResetToken] = useState(''); const [message, setMessage] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const switchMode = (next) => { setError(''); setMessage(''); onMode(next); };
  const submit = async (event) => {
    event.preventDefault(); setBusy(true); setError(''); setMessage('');
    try {
      if (mode === 'forgot') { const result = await api('/api/auth/forgot-password', { method: 'POST', body: JSON.stringify({ email }) }); setResetToken(result.resetToken || ''); setMessage(result.resetToken ? `Local demo reset code: ${result.resetToken}` : result.message); if (result.resetToken) onMode('reset'); }
      else if (mode === 'reset') { await api('/api/auth/reset-password', { method: 'POST', body: JSON.stringify({ token: resetToken, password }) }); setMessage('Password updated. Sign in with your new password.'); onMode('login'); }
      else { const body = mode === 'register' ? { name, email, password, role } : { email, password }; await onSuccess(await api(`/api/auth/${mode === 'register' ? 'register' : 'login'}`, { method: 'POST', body: JSON.stringify(body) })); }
    } catch (err) { setError(err.message); } finally { setBusy(false); }
  };
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><div className="auth-modal" role="dialog" aria-modal="true" aria-label="Account access"><button className="modal-close" onClick={onClose} aria-label="Close">x</button><span className="eyebrow">ONEPLACE ACCOUNT</span><h2>{({ login: 'Welcome back', register: 'Join OnePlace', forgot: 'Reset your password', reset: 'Choose a new password' })[mode]}</h2><p>{mode === 'login' ? 'Sign in to check out, sell, and see your orders.' : mode === 'register' ? 'Create an account to get more from your dashboard.' : 'Get back into your account.'}</p><form className="form" onSubmit={submit}>{mode === 'register' && <><label>Your name<input required value={name} onChange={(event) => setName(event.target.value)} /></label><label>Account type<select value={role} onChange={(event) => setRole(event.target.value)}><option value="STUDENT">Student</option><option value="TEACHER">Teacher</option><option value="BUYER">Buyer</option><option value="SELLER">Seller</option></select></label></>}{mode !== 'reset' && <label>{mode === 'login' ? 'Email or admin username' : 'Email'}<input required type={mode === 'login' ? 'text' : 'email'} value={email} onChange={(event) => setEmail(event.target.value)} /></label>}{mode === 'reset' && <label>Reset code<input required value={resetToken} onChange={(event) => setResetToken(event.target.value)} /></label>}{mode !== 'forgot' && <label>Password<input required type="password" minLength={mode === 'login' ? 1 : 8} value={password} onChange={(event) => setPassword(event.target.value)} /></label>}{error && <p className="inline-error">{error}</p>}{message && <p className="success-message">{message}</p>}<button className="primary" disabled={busy}>{busy ? 'Please wait...' : ({ login: 'Sign in', register: 'Create account', forgot: 'Get reset code', reset: 'Update password' })[mode]}</button></form><div className="auth-links">{mode === 'login' ? <><button onClick={() => switchMode('register')}>Create an account</button><button onClick={() => switchMode('forgot')}>Forgot password?</button></> : <button onClick={() => switchMode('login')}>Back to sign in</button>}</div></div></div>;
}

export default App;
