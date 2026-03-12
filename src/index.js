import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import './index.css';
import App from './App';
import ErrorBoundary from './components/ErrorBoundary';
import reportWebVitals from './reportWebVitals';

// Lazy load admin app (Leaflet, heavy dashboard) so main app loads faster on mobile
const AdminApp = lazy(() => import('./AdminApp'));

function LoadingFallback() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-100">
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-400 border-t-transparent" />
    </div>
  );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route
          path="/admin/*"
          element={
            <Suspense fallback={<LoadingFallback />}>
              <AdminApp />
            </Suspense>
          }
        />
        <Route path="*" element={<ErrorBoundary><App /></ErrorBoundary>} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
);

reportWebVitals();
