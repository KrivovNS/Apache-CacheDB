import React, { useState, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import Layout from './components/Layout/Layout';
import CacheOperations from './components/CacheOperations/CacheOperations';
import UserManagement from './components/UserManagement/UserManagement';
import Configuration from './components/Configuration/Configuration';
import QueryConsole from './components/QueryConsole/QueryConsole';
import LoginModal from './components/Common/LoginModal';
import { AuthProvider, useAuth } from './hooks/useAuth';
import { NotificationProvider } from './hooks/useNotification';
import styles from './App.module.css';

// Внутренний компонент для обработки показа LoginModal
const AppContent = () => {
  const [showLogin, setShowLogin] = useState(false);
  const { isAuthenticated, loading } = useAuth();

  // Слушаем событие показа логина из useAuth
  useEffect(() => {
    const handleShowLogin = () => setShowLogin(true);
    window.addEventListener('showLogin', handleShowLogin);
    return () => window.removeEventListener('showLogin', handleShowLogin);
  }, []);

  // Показываем логин если не авторизован
  useEffect(() => {
    if (!isAuthenticated && !loading) {
      setShowLogin(true);
    }
  }, [isAuthenticated, loading]);

  if (loading) {
    return <div className={styles.loading}>Loading...</div>;
  }

  return (
    <>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Navigate to="/cache" />} />
          <Route path="cache" element={<CacheOperations />} />
          <Route path="users" element={<UserManagement />} />
          <Route path="config" element={<Configuration />} />
          <Route path="console" element={<QueryConsole />} />
        </Route>
      </Routes>

      <LoginModal
        isOpen={showLogin}
        onClose={() => setShowLogin(false)}
      />
    </>
  );
};

function App() {
  return (
    <NotificationProvider>
      <AuthProvider>
        <Router>
          <div className={styles.app}>
            <Toaster
              position="top-right"
              toastOptions={{
                duration: 4000,
                style: {
                  background: '#363636',
                  color: '#fff',
                },
              }}
            />
            <AppContent />
          </div>
        </Router>
      </AuthProvider>
    </NotificationProvider>
  );
}

export default App;