import { fireEvent, render, screen, within } from '@testing-library/react';
import AdminPanel from './AdminPanel';
import { api } from './api';

jest.mock('./api', () => ({ api: jest.fn() }));

test('keeps user management available when catalog and feed fail', async () => {
  api.mockImplementation(path => {
    if (path === '/api/auth/admin/users') return Promise.resolve([{ id: 1, name: 'admin', role: 'ADMIN', email: 'admin@oneplace.local' }, { id: 2, name: 'Alex', role: 'STUDENT', email: 'alex@example.com' }]);
    if (path === '/api/products' || path.startsWith('/api/feed?page=')) return Promise.reject(Object.assign(new Error('Unavailable'), { status: 503 }));
    return Promise.resolve([]);
  });
  render(<AdminPanel token="session" user={{ id: 1, role: 'ADMIN' }} onProductsChanged={jest.fn()} />);
  await screen.findByText('Alex');
  const users = screen.getByRole('heading', { name: 'Users' });
  const usersSection = users.closest('section');
  expect(within(usersSection).getByText('Alex')).toBeInTheDocument();
  expect(within(usersSection).getAllByRole('button', { name: 'Edit' })).toHaveLength(2);
  expect(within(usersSection).getByRole('button', { name: 'Delete' })).toBeEnabled();
  const toggle = within(users).getByRole('button', { name: 'Users' });
  fireEvent.click(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getAllByText('Could not load (503).')).toHaveLength(2);
});
