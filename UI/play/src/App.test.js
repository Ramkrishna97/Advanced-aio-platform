import { act, fireEvent, render, screen } from '@testing-library/react';
import App, { PhotoCropDialog } from './App';
import { api } from './api';
jest.mock('./api', () => ({ api: jest.fn(), recordActivity: jest.fn() }));
const profile = { id: 1, name: 'Alex', email: 'alex@example.com', role: 'BUYER', seller: true, hasPhoto: false };
beforeEach(() => {
  jest.useFakeTimers(); localStorage.clear(); window.history.replaceState({}, '', '/');
  localStorage.setItem('oneplace_token', 'session'); localStorage.setItem('oneplace_profile', JSON.stringify(profile));
});
afterEach(() => { jest.useRealTimers(); jest.resetAllMocks(); });
test('recovers purchase history without refresh and removes the service warning', async () => {
  let attempts = 0;
  api.mockImplementation(async path => {
    if (path === '/api/auth/me') return profile;
    if (path === '/api/orders' && attempts++ === 0) throw new Error('Orders temporarily unavailable');
    return [];
  });
  await act(async () => { render(<App />); });
  expect(screen.getByText(/Orders temporarily unavailable/)).toBeInTheDocument();
  await act(async () => { jest.advanceTimersByTime(5000); });
  expect(screen.queryByText(/Orders temporarily unavailable/)).not.toBeInTheDocument();
  expect(attempts).toBe(2);
  expect(screen.getByRole('button', { name: 'Seller studio', exact: true })).toBeInTheDocument();
});
test('reconnects identity after startup without discarding the saved seller account', async () => {
  let attempts = 0;
  api.mockImplementation(async path => {
    if (path === '/api/auth/me') { if (attempts++ === 0) throw new Error('Identity temporarily unavailable'); return profile; }
    return [];
  });
  await act(async () => { render(<App />); });
  expect(screen.getByText(/Identity temporarily unavailable/)).toBeInTheDocument();
  await act(async () => { jest.advanceTimersByTime(5000); });
  expect(screen.queryByText(/Identity temporarily unavailable/)).not.toBeInTheDocument();
  expect(JSON.parse(localStorage.getItem('oneplace_profile')).seller).toBe(true);
});

test('dragging the crop changes the source position and releases the decoded image', async () => {
  const bitmap = { width: 512, height: 256, close: jest.fn() };
  global.createImageBitmap = jest.fn().mockResolvedValue(bitmap);
  const drawImage = jest.fn();
  const context = jest.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ fillRect: jest.fn(), drawImage });
  const oldPointerEvent = window.PointerEvent;
  window.PointerEvent = MouseEvent;
  let view;
  await act(async () => { view = render(<PhotoCropDialog file={new File(['image'], 'photo.jpg')} onClose={() => {}} onApply={() => {}} />); });
  const canvas = view.container.querySelector('canvas');
  canvas.setPointerCapture = jest.fn();
  canvas.getBoundingClientRect = () => ({ width: 256 });
  fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100 });
  fireEvent.pointerMove(canvas, { clientX: 150, clientY: 100 });
  expect(drawImage.mock.calls.at(-1)[1]).toBe(78);
  fireEvent.pointerUp(canvas);
  view.unmount();
  expect(bitmap.close).toHaveBeenCalled();
  context.mockRestore(); window.PointerEvent = oldPointerEvent;
  delete global.createImageBitmap;
});
