import React, { useState, useEffect } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import api from '../../services/api';
import { FiSave, FiRefreshCw, FiInfo } from 'react-icons/fi';
import styles from './Configuration.module.css';

const MEMORY_POLICIES = [
  'noeviction',
  'allkeys-lru',
  'volatile-lru',
  'allkeys-lfu',
  'volatile-lfu'
];

const Configuration = () => {
  const { user, isAuthenticated, isSuperAdmin } = useAuth();
  const { showSuccess, showError } = useNotification();

  const [config, setConfig] = useState({
    max_memory_policy: 'allkeys-lru',
    max_storage_memory: 104857600, // 100MB default
    persistence: false
  });

  const [loading, setLoading] = useState(false);
  const [stats, setStats] = useState(null);

  useEffect(() => {
    if (isAuthenticated) {
      loadStats();
    }
  }, [isAuthenticated]);

  const loadStats = async () => {
    try {
      // Note: You'll need to implement a GET /stats endpoint
      const response = await api.get('/stats');
      setStats(response.data);
    } catch (error) {
      // Silently fail - stats might not be available
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);

    try {
      await api.updateConfig(
        config.max_memory_policy,
        config.max_storage_memory,
        config.persistence
      );
      showSuccess('Configuration updated successfully');
    } catch (error) {
      showError(error.response?.data || 'Failed to update configuration');
    } finally {
      setLoading(false);
    }
  };

  const formatBytes = (bytes) => {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  if (!isAuthenticated) {
    return (
      <div className={styles.warning}>
        <h2>Please login to access Configuration</h2>
      </div>
    );
  }

  if (!isSuperAdmin) {
    return (
      <div className={styles.warning}>
        <h2>Access Denied</h2>
        <p>Only SUPERADMIN users can modify configuration</p>
      </div>
    );
  }

  return (
    <div className={styles.configuration}>
      <div className={styles.header}>
        <h1>Configuration</h1>
      </div>

      <div className={styles.content}>
        <div className={styles.configForm}>
          <form onSubmit={handleSubmit}>
            <div className={styles.formGroup}>
              <label>Max Memory Policy</label>
              <select
                value={config.max_memory_policy}
                onChange={(e) => setConfig({...config, max_memory_policy: e.target.value})}
                disabled={loading}
              >
                {MEMORY_POLICIES.map(policy => (
                  <option key={policy} value={policy}>{policy}</option>
                ))}
              </select>
              <div className={styles.help}>
                <FiInfo />
                <span>Determines how keys are evicted when memory limit is reached</span>
              </div>
            </div>

            <div className={styles.formGroup}>
              <label>Max Storage Memory (bytes)</label>
              <input
                type="number"
                value={config.max_storage_memory}
                onChange={(e) => setConfig({...config, max_storage_memory: parseInt(e.target.value)})}
                min="1024"
                step="1024"
                disabled={loading}
              />
              <div className={styles.help}>
                <FiInfo />
                <span>Maximum memory in bytes ({formatBytes(config.max_storage_memory)})</span>
              </div>
            </div>

            <div className={styles.formGroup}>
              <label>Persistence</label>
              <div className={styles.checkboxGroup}>
                <input
                  type="checkbox"
                  checked={config.persistence}
                  onChange={(e) => setConfig({...config, persistence: e.target.checked})}
                  disabled={loading}
                />
                <span>Enable data persistence to disk</span>
              </div>
            </div>

            <button
              type="submit"
              className={styles.submitBtn}
              disabled={loading}
            >
              <FiSave /> Update Configuration
            </button>
          </form>
        </div>

        {stats && (
          <div className={styles.stats}>
            <h2>Statistics</h2>

            <div className={styles.statsGrid}>
              <div className={styles.statCard}>
                <h3>Total Keys</h3>
                <p>{stats.totalKeys || 0}</p>
              </div>

              <div className={styles.statCard}>
                <h3>Memory Used</h3>
                <p>{formatBytes(stats.memoryUsed || 0)}</p>
              </div>

              <div className={styles.statCard}>
                <h3>Memory Limit</h3>
                <p>{formatBytes(stats.memoryLimit || config.max_storage_memory)}</p>
              </div>

              <div className={styles.statCard}>
                <h3>Hit Ratio</h3>
                <p>{stats.hitRatio ? `${(stats.hitRatio * 100).toFixed(1)}%` : '0%'}</p>
              </div>

              <div className={styles.statCard}>
                <h3>Total Gets</h3>
                <p>{stats.totalGets || 0}</p>
              </div>

              <div className={styles.statCard}>
                <h3>Total Sets</h3>
                <p>{stats.totalSets || 0}</p>
              </div>
            </div>

            <button
              className={styles.refreshBtn}
              onClick={loadStats}
            >
              <FiRefreshCw /> Refresh Stats
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default Configuration;