import React, { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import { FiUserPlus, FiUserMinus, FiEdit2, FiSave, FiX, FiKey, FiShield } from 'react-icons/fi';
import styles from './UserManagement.module.css';
import api from '../../services/api';

const PERMISSIONS = ['reader', 'admin', 'superadmin'];

const UserManagement = () => {
  const { user, isAuthenticated, isSuperAdmin, logout } = useAuth();
  const { showSuccess, showError } = useNotification();

  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [formData, setFormData] = useState({
    login: '',
    password: '',
    permission: 'reader'
  });

  const parseUsers = useCallback((payload) => {
    try {
      const rawUsers = typeof payload === 'string' ? JSON.parse(payload) : payload;
      if (!Array.isArray(rawUsers)) {
        return [];
      }

      return rawUsers.map((entry) => ({
        id: entry.id,
        login: entry.login || entry.username,
        permission: entry.permission
      }));
    } catch (error) {
      console.error('Failed to parse users payload:', error);
      return [];
    }
  }, []);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const response = await api.getUsers();
      setUsers(parseUsers(response.data));
    } catch (error) {
      showError(error.message || 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, [parseUsers, showError]);

  useEffect(() => {
    if (isAuthenticated && isSuperAdmin) {
      loadUsers();
    }
  }, [isAuthenticated, isSuperAdmin, loadUsers]);

  const handleCreateUser = async (e) => {
    e.preventDefault();
    setLoading(true);

    try {
      const response = await api.createUser(
        formData.login,
        formData.password,
        formData.permission
      );

      let newUserId = users.length + 1;
      if (response?.data) {
        const idMatch = response.data.match(/ID:\s*(\d+)/i);
        if (idMatch) {
          newUserId = parseInt(idMatch[1], 10);
        }
      }

      const newUser = {
        id: newUserId,
        login: formData.login,
        permission: formData.permission
      };

      setUsers(prev => [...prev, newUser]);
      await loadUsers();
      showSuccess('User created successfully');
      setShowCreateForm(false);
      setFormData({ login: '', password: '', permission: 'reader' });
    } catch (error) {
      showError(error.message || 'Failed to create user');
    } finally {
      setLoading(false);
    }
  };

  const handleUpdateUser = async (e) => {
    e.preventDefault();
    setLoading(true);

    try {
      const updates = {};

      if (formData.login && formData.login !== editingUser.login) {
        updates.new_login = formData.login;
      }
      if (formData.password && formData.password.trim() !== '') {
        updates.password = formData.password;
      }
      if (formData.permission && formData.permission !== editingUser.permission) {
        updates.permission = formData.permission;
      }

      await api.updateUser(editingUser.login, updates);

      const updatedLogin = updates.new_login || editingUser.login;
      const updatedPermission = updates.permission || editingUser.permission;

      setUsers(prev => prev.map((u) =>
        u.id === editingUser.id
          ? {
              ...u,
              login: updatedLogin,
              permission: updatedPermission
            }
          : u
      ));
      await loadUsers();

      const isEditingSelf = user && editingUser && editingUser.login === user.username;

      showSuccess(
        isEditingSelf
          ? 'User updated successfully. Please log in again with the new credentials.'
          : 'User updated successfully'
      );
      setEditingUser(null);
      setFormData({ login: '', password: '', permission: 'reader' });

      if (isEditingSelf) {
        logout();
      }
    } catch (error) {
      showError(error.message || 'Failed to update user');
    } finally {
      setLoading(false);
    }
  };

  const handleDeleteUser = async (login) => {
    if (!window.confirm(`Are you sure you want to delete user "${login}"?`)) {
      return;
    }

    setLoading(true);
    try {
      await api.deleteUser(login);
      setUsers(prev => prev.filter(u => u.login !== login));
      await loadUsers();
      showSuccess('User deleted successfully');
    } catch (error) {
      showError(error.message || 'Failed to delete user');
    } finally {
      setLoading(false);
    }
  };

  const startEditing = (user) => {
    setEditingUser(user);
    setFormData({
      login: user.login,
      password: '',
      permission: user.permission
    });
  };

  const cancelEditing = () => {
    setEditingUser(null);
    setShowCreateForm(false);
    setFormData({ login: '', password: '', permission: 'reader' });
  };

  if (!isAuthenticated) {
    return (
      <div className={styles.warning}>
        <h2>Please login to access User Management</h2>
      </div>
    );
  }

  if (!isSuperAdmin) {
    return (
      <div className={styles.warning}>
        <h2>Access Denied</h2>
        <p>Only SUPERADMIN users can manage users</p>
      </div>
    );
  }

  return (
    <div className={styles.userManagement}>
      <div className={styles.header}>
        <h1>User Management</h1>
        <button
          className={styles.createBtn}
          onClick={() => setShowCreateForm(true)}
        >
          <FiUserPlus /> Create User
        </button>
      </div>

      {(showCreateForm || editingUser) && (
        <div className={styles.formModal}>
          <div className={styles.formContainer}>
            <h2>{editingUser ? 'Edit User' : 'Create New User'}</h2>

            <form onSubmit={editingUser ? handleUpdateUser : handleCreateUser}>
              <div className={styles.formGroup}>
                <label>
                  <FiUserPlus /> Login
                </label>
                <input
                  type="text"
                  value={formData.login}
                  onChange={(e) => setFormData({...formData, login: e.target.value})}
                  placeholder="Enter login"
                  required
                  disabled={loading}
                />
              </div>

              <div className={styles.formGroup}>
                <label>
                  <FiKey /> Password
                </label>
                <input
                  type="password"
                  value={formData.password}
                  onChange={(e) => setFormData({...formData, password: e.target.value})}
                  placeholder={editingUser ? "Leave empty to keep current" : "Enter password"}
                  required={!editingUser}
                  disabled={loading}
                />
              </div>

              <div className={styles.formGroup}>
                <label>
                  <FiShield /> Permission
                </label>
                <select
                  value={formData.permission}
                  onChange={(e) => setFormData({...formData, permission: e.target.value})}
                  disabled={loading || (editingUser && editingUser.login === user?.username)}
                >
                  {PERMISSIONS.map(perm => (
                    <option key={perm} value={perm}>{perm.toUpperCase()}</option>
                  ))}
                </select>
              </div>

              <div className={styles.formActions}>
                <button
                  type="submit"
                  className={styles.saveBtn}
                  disabled={loading}
                >
                  <FiSave /> {editingUser ? 'Update' : 'Create'}
                </button>
                <button
                  type="button"
                  className={styles.cancelBtn}
                  onClick={cancelEditing}
                  disabled={loading}
                >
                  <FiX /> Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      <div className={styles.userList}>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Login</th>
              <th>Permission</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan="4" className={styles.loadingCell}>
                  Loading...
                </td>
              </tr>
            ) : users.length === 0 ? (
              <tr>
                <td colSpan="4" className={styles.emptyCell}>
                  No users found
                </td>
              </tr>
            ) : (
              users.map(user => (
                <tr key={user.id}>
                  <td>{user.id}</td>
                  <td>{user.login}</td>
                  <td>
                    <span className={`${styles.permissionBadge} ${styles[user.permission]}`}>
                      {user.permission}
                    </span>
                  </td>
                  <td>
                    <div className={styles.actions}>
                      <button
                        className={styles.editBtn}
                        onClick={() => startEditing(user)}
                        disabled={false}
                      >
                        <FiEdit2 />
                      </button>
                      <button
                        className={styles.deleteBtn}
                        onClick={() => handleDeleteUser(user.login)}
                        disabled={user.login === 'default'}
                      >
                        <FiUserMinus />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default UserManagement;
