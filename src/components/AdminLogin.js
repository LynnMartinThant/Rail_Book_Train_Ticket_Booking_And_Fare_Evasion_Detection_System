import React, { useState } from 'react';
import * as api from '../api/client';

const ADMIN_SECRET_STORAGE_KEY = 'railbook_admin_secret';

function AdminLogin({ onSuccess }) {
  const [secret, setSecret] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!secret.trim()) return;
    setError(null);
    setLoading(true);
    try {
      await api.getAdminGeofences(secret.trim());
      window.localStorage.setItem(ADMIN_SECRET_STORAGE_KEY, secret.trim());
      onSuccess(secret.trim());
    } catch (e) {
      setError(e.message || 'Invalid admin secret');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-900 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="rounded-2xl border border-slate-700 bg-slate-800 shadow-xl p-8">
          <div className="text-center mb-8">
            <h1 className="text-2xl font-bold text-white tracking-tight">RailBook</h1>
            <p className="text-slate-400 text-sm mt-1">Admin sign in</p>
          </div>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label htmlFor="admin-secret" className="block text-xs font-medium text-slate-400 mb-1">
                Admin secret
              </label>
              <input
                id="admin-secret"
                type="password"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                placeholder="Enter secret"
                className="w-full rounded-lg border border-slate-600 bg-slate-700/50 px-4 py-3 text-white placeholder-slate-500 focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                autoFocus
                required
              />
            </div>
            {error && (
              <p className="text-sm text-red-400" role="alert">{error}</p>
            )}
            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-lg bg-indigo-600 px-4 py-3 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          <p className="text-xs text-slate-500 mt-6 text-center">
            Use the secret configured in <code className="text-slate-400">booking.admin.secret</code>
          </p>
        </div>
      </div>
    </div>
  );
}

export default AdminLogin;
