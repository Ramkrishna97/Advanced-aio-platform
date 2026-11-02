import { fireEvent, render, screen } from '@testing-library/react';
import AdminEditDialog from './AdminEditDialog';
import { api } from './api';

jest.mock('./api', () => ({ api: jest.fn() }));

test('admin can save edited post fields', async () => {
  api.mockResolvedValue({});
  render(<AdminEditDialog kind="post" item={{ id: 9, title: 'Old', body: 'Body', type: 'POST' }} users={[]} token="session" onClose={jest.fn()} onSaved={jest.fn()} />);
  fireEvent.change(screen.getByRole('textbox', { name: 'Title' }), { target: { value: 'Updated' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
  await screen.findByRole('button', { name: 'Save changes' });
  expect(api).toHaveBeenCalledWith('/api/feed/9', expect.objectContaining({ method: 'PUT', body: expect.stringContaining('Updated') }), 'session');
});
