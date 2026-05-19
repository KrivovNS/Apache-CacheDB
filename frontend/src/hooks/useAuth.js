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
    const storedUser = localStorage.getItem('cacheDbUser');

    if (token && storedUser) {
      try {
        api.setSessionToken(token);
        const parsed = JSON.parse(storedUser);
        setUser(parsed);
        setIsAuthenticated(true);
        setIsSuperAdmin(parsed.permission === 'superadmin');
      } catch (error) {
        console.error('Failed to parse stored user:', error);
        localStorage.removeItem('cacheDbUser');
        localStorage.removeItem('sessionToken');
        api.clearSession();
      }
    } else {
      api.clearSession();
    }
    setLoading(false);
  }, []);

  const authenticate = async (login, password) => {
    try {
      console.log('Attempting login with:', login);
      const result = await api.login(login, password);
      console.log('Login result:', result);

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
      api.clearSession();
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