import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import axios from 'axios';
import App from './App';

global.IS_REACT_ACT_ENVIRONMENT = true;

jest.mock('axios', () => ({
  get: jest.fn()
}));

test('renders the CRUD demo shell', async () => {
  axios.get.mockResolvedValueOnce({ data: [] });

  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);

  await act(async () => {
    root.render(<App />);
  });

  expect(container.textContent).toContain('MERN Stack CI/CD Demo');
  expect(container.textContent).toContain('No items yet. Add one above!');

  await act(async () => {
    root.unmount();
  });
  container.remove();
});
