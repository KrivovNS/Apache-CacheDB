import { useContext, createContext, useState, useEffect } from 'react';
import api from '../services/api';

const AuthContext = createContext();

export const useAuth = () => {
  return useContext(AuthContext);
};

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem('sessionToken');
    if (token) {
      api.setSessionToken(token);
      const storedUser = localStorage.getItem('cacheDbUser');
      if (storedUser) {
        try {
          const parsed = JSON.parse(storedUser);
          setUser(parsed);
          setIsAuthenticated(true);
          setIsSuperAdmin(!!parsed.isSuperAdmin);
        } catch {
          // Если парсинг не удался, очищаем некорректные данные
          localStorage.removeItem('cacheDbUser');
        }
      } else {
        // Если информации о пользователе нет, считаем, что нужно перелогиниться
        api.clearSession();
      }
    }
    setLoading(false);
  }, []);

  const authenticate = async (login, password) => {
    try {
      console.log('Attempting login with:', login);
      const result = await api.login(login, password);
      console.log('Login result:', result);

      // Parse user info from response
      const userMatch = result.match(/User: (\w+)/);
      const permissionMatch = result.match(/Permission: (\w+)/);

      const username = userMatch ? userMatch[1] : login;
      const permission = permissionMatch ? permissionMatch[1] : 'superadmin';

      const userData = {
        username,
        permission,
        isSuperAdmin: permission === 'superadmin'
      };

      setUser(userData);
      setIsAuthenticated(true);
      setIsSuperAdmin(permission === 'superadmin');

      localStorage.setItem('cacheDbUser', JSON.stringify(userData));

      return userData;
    } catch (error) {
      console.error('Login error:', error);
      throw error;
    }
  };

  const logout = () => {
    api.clearSession();
    localStorage.removeItem('cacheDbUser');
    setUser(null);
    setIsAuthenticated(false);
    setIsSuperAdmin(false);
  };

  const login = () => {
    window.dispatchEvent(new CustomEvent('showLogin'));
  };

  const value = {
    user,
    isAuthenticated,
    isSuperAdmin,
    loading,
    authenticate,
    logout,
    login
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
};