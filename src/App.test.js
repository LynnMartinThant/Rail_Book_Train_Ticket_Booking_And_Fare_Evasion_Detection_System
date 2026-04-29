import { render, screen } from '@testing-library/react';
import App from './App';

// Smoke test: app renders (landing or RailBook branding). See TESTING.md for full flows.
test('renders RailBook app', () => {
  render(<App />);
  const railBookElements = screen.getAllByText(/RailBook/i);
  expect(railBookElements.length).toBeGreaterThanOrEqual(1);
});
