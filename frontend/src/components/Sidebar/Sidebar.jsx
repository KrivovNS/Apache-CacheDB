import React from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import {
  FiDatabase,
  FiUsers,
  FiTerminal,
  FiLogIn,
  FiLogOut,
  FiUser
} from 'react-icons/fi';
import styles from './Sidebar.module.css';

const Sidebar = () => {
  const { user, isSuperAdmin, logout, login } = useAuth();

  return (
    <div className={styles.sidebar}>
      <div className={styles.logo}>
        <h2>CacheDB</h2>
        <p>v1.0.0</p>
      </div>

      <nav className={styles.nav}>
        <NavLink
          to="/cache"
          className={({ isActive }) =>
            isActive ? `${styles.navLink} ${styles.active}` : styles.navLink
          }
        >
          <FiDatabase className={styles.icon} />
          <span>Cache Operations</span>
        </NavLink>

        {isSuperAdmin && (
          <>
            <NavLink
              to="/users"
              className={({ isActive }) =>
                isActive ? `${styles.navLink} ${styles.active}` : styles.navLink
              }
            >
              <FiUsers className={styles.icon} />
              <span>User Management</span>
            </NavLink>
          </>
        )}

        <NavLink
          to="/console"
          className={({ isActive }) =>
            isActive ? `${styles.navLink} ${styles.active}` : styles.navLink
          }
        >
          <FiTerminal className={styles.icon} />
          <span>Query Console</span>
        </NavLink>
      </nav>

      <div className={styles.userSection}>
        {user ? (
          <div className={styles.userInfo}>
            <div className={styles.userAvatar}>
              <FiUser />
            </div>
            <div className={styles.userDetails}>
              <span className={styles.userName}>{user.username}</span>
              <span className={styles.userRole}>{user.permission}</span>
            </div>
            <button onClick={logout} className={styles.logoutBtn}>
              <FiLogOut />
            </button>
          </div>
        ) : (
          <button onClick={login} className={styles.loginBtn}>
            <FiLogIn className={styles.icon} />
            <span>Login</span>
          </button>
        )}
      </div>
    </div>
  );
};

export default Sidebar;
