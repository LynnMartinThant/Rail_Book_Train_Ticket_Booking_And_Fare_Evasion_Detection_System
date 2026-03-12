import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import * as api from './api/client';
import AdminLogin from './components/AdminLogin';
import AdminDashboard from './components/AdminDashboard';

const ADMIN_SECRET_STORAGE_KEY = 'railbook_admin_secret';

function AdminApp() {
  const navigate = useNavigate();
  const location = useLocation();
  const [authenticated, setAuthenticated] = useState(false);
  const [secret, setSecret] = useState('');
  const [checking, setChecking] = useState(true);

  const validateSecret = useCallback(async (s) => {
    if (!s) return false;
    try {
      await api.getAdminGeofences(s);
      return true;
    } catch {
      return false;
    }
  }, []);

  useEffect(() => {
    const stored = window.localStorage.getItem(ADMIN_SECRET_STORAGE_KEY);
    if (!stored) {
      setChecking(false);
      return;
    }
    validateSecret(stored).then((ok) => {
      setChecking(false);
      if (ok) {
        setSecret(stored);
        setAuthenticated(true);
        if (location.pathname === '/admin' || location.pathname === '/admin/') {
          navigate('/admin/dashboard', { replace: true });
        }
      } else {
        window.localStorage.removeItem(ADMIN_SECRET_STORAGE_KEY);
      }
    });
  }, [validateSecret, location.pathname, navigate]);

  const handleLoginSuccess = (s) => {
    setSecret(s);
    setAuthenticated(true);
    navigate('/admin/dashboard', { replace: true });
  };

  const handleLogout = () => {
    window.localStorage.removeItem(ADMIN_SECRET_STORAGE_KEY);
    setSecret('');
    setAuthenticated(false);
    navigate('/admin', { replace: true });
  };

  if (checking) {
    return (
      <div className="min-h-screen bg-slate-900 flex items-center justify-center">
        <p className="text-slate-400">Loading…</p>
      </div>
    );
  }

  if (!authenticated) {
    return <AdminLogin onSuccess={handleLoginSuccess} />;
  }

  return (
    <AdminDashboard
      secret={secret}
      onLogout={handleLogout}
    />
  );
}

export default AdminApp;
