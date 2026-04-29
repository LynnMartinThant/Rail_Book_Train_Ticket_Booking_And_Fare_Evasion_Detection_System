import React from 'react';

/**
 * Catches JavaScript errors in the child tree so the app doesn't white-screen
 * (e.g. when Chrome device toolbar resizes the viewport and something throws).
 */
class ErrorBoundary extends React.Component {
  state = { hasError: false, error: null };

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      const err = this.state.error;
      const message = err?.message || String(err);
      return (
        <div className="min-h-screen bg-slate-100 flex flex-col items-center justify-center p-6 text-center">
          <p className="text-lg font-semibold text-slate-800 mb-2">Something went wrong</p>
          <p className="text-sm text-slate-600 mb-4 max-w-md">
            The app hit an error. This can happen when resizing the window or switching device view. Try refreshing the page.
          </p>
          {message && (
            <pre className="mb-4 p-3 rounded bg-slate-200 text-left text-xs text-slate-700 overflow-auto max-w-md max-h-24">
              {message}
            </pre>
          )}
          <button
            type="button"
            onClick={() => this.setState({ hasError: false, error: null })}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Try again
          </button>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="mt-3 rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Refresh page
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
