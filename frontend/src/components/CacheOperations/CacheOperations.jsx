import React, { useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import api from '../../services/api';
import ReactJson from 'react-json-view';
import {
  FiSearch,
  FiPlus,
  FiEdit,
  FiTrash2,
  FiCopy,
  FiClock,
  FiKey,
  FiType
} from 'react-icons/fi';
import styles from './CacheOperations.module.css';

const DATA_TYPES = ['string', 'json', 'byte[]'];
const TTL_UNITS = ['ms', 's', 'm', 'h', 'd'];

const CacheOperations = () => {
  const { user, isAuthenticated } = useAuth();
  const { showSuccess, showError } = useNotification();

  const [activeTab, setActiveTab] = useState('get');
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [dataType, setDataType] = useState('string');
  const [ttl, setTtl] = useState('');
  const [ttlUnit, setTtlUnit] = useState('s');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);

  const canWrite = user?.permission !== 'reader';

  const handleGet = async () => {
    if (!key) {
      showError('Key is required');
      return;
    }

    setLoading(true);
    try {
      const response = await api.getCache(key);
      setResult({
        type: 'get',
        data: response.data,
        success: true
      });
      addToHistory('GET', key, response.data);
      showSuccess('Data retrieved successfully');
    } catch (error) {
      setResult({
        type: 'get',
        error: error.response?.data || error.message,
        success: false
      });
      showError(error.response?.data || 'Failed to get data');
    } finally {
      setLoading(false);
    }
  };

  const handleSet = async (method) => {
    if (!key || !value) {
      showError('Key and value are required');
      return;
    }

    setLoading(true);
    try {
      const ttlString = ttl ? `${ttl}${ttlUnit}` : null;
      console.log(`Calling setCache with ${method}:`, { key, value, dataType, ttl: ttlString });

      const response = await api.setCache(method, key, value, dataType, ttlString);
      console.log('setCache response:', response);

      setResult({
        type: method,
        data: response.data,
        success: true
      });

      addToHistory(method.toUpperCase(), key, value, dataType, ttlString);
      showSuccess(`Data ${method === 'post' ? 'created' : 'updated'} successfully`);

      // Clear form for POST (create)
      if (method === 'post') {
        setKey('');
        setValue('');
        setTtl('');
      }
    } catch (error) {
      console.error(`Error in ${method}:`, error);
      setResult({
        type: method,
        error: error.response?.data || error.message,
        success: false
      });
      showError(error.response?.data || `Failed to ${method} data`);
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    if (!key) {
      showError('Key is required');
      return;
    }

    if (!window.confirm(`Are you sure you want to delete key "${key}"?`)) {
      return;
    }

    setLoading(true);
    try {
      console.log('Calling deleteCache with key:', key);
      const response = await api.deleteCache(key);
      console.log('deleteCache response:', response);

      setResult({
        type: 'delete',
        data: response.data,
        success: true
      });
      addToHistory('DELETE', key);
      showSuccess('Data deleted successfully');
      setKey('');
      setValue('');
    } catch (error) {
      console.error('Error in delete:', error);
      setResult({
        type: 'delete',
        error: error.response?.data || error.message,
        success: false
      });
      showError(error.response?.data || 'Failed to delete data');
    } finally {
      setLoading(false);
    }
  };

  const addToHistory = (operation, key, value = null, type = null, ttl = null) => {
    setHistory(prev => [{
      operation,
      key,
      value,
      type,
      ttl,
      timestamp: new Date().toISOString()
    }, ...prev.slice(0, 9)]);
  };

  const formatValue = (value) => {
    if (typeof value === 'string') {
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    }
    return value;
  };

  if (!isAuthenticated) {
    return (
      <div className={styles.warning}>
        <h2>Please login to use Cache Operations</h2>
      </div>
    );
  }

  return (
    <div className={styles.cacheOperations}>
      <div className={styles.header}>
        <h1>Cache Operations</h1>
        {user && (
          <div className={styles.userBadge}>
            Logged in as <strong>{user.username}</strong> ({user.permission})
          </div>
        )}
      </div>

      <div className={styles.tabs}>
        <button
          className={`${styles.tab} ${activeTab === 'get' ? styles.active : ''}`}
          onClick={() => setActiveTab('get')}
        >
          <FiSearch /> GET
        </button>
        <button
          className={`${styles.tab} ${activeTab === 'post' ? styles.active : ''}`}
          onClick={() => canWrite && setActiveTab('post')}
          disabled={!canWrite}
          title={!canWrite ? 'Only ADMIN and SUPERADMIN can create data' : undefined}
        >
          <FiPlus /> POST (Create)
        </button>
        <button
          className={`${styles.tab} ${activeTab === 'put' ? styles.active : ''}`}
          onClick={() => canWrite && setActiveTab('put')}
          disabled={!canWrite}
          title={!canWrite ? 'Only ADMIN and SUPERADMIN can update data' : undefined}
        >
          <FiEdit /> PUT (Update)
        </button>
        <button
          className={`${styles.tab} ${activeTab === 'delete' ? styles.active : ''}`}
          onClick={() => canWrite && setActiveTab('delete')}
          disabled={!canWrite}
          title={!canWrite ? 'Only ADMIN and SUPERADMIN can delete data' : undefined}
        >
          <FiTrash2 /> DELETE
        </button>
      </div>

      <div className={styles.content}>
        <div className={styles.inputSection}>
          <div className={styles.keyInput}>
            <FiKey className={styles.inputIcon} />
            <input
              type="text"
              placeholder="Enter key"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              disabled={loading}
            />
          </div>

          {(activeTab === 'post' || activeTab === 'put') && (
            <>
              <div className={styles.typeSelector}>
                <FiType className={styles.inputIcon} />
                <select
                  value={dataType}
                  onChange={(e) => setDataType(e.target.value)}
                  disabled={loading}
                >
                  {DATA_TYPES.map(type => (
                    <option key={type} value={type}>{type}</option>
                  ))}
                </select>
              </div>

              <div className={styles.ttlInput}>
                <FiClock className={styles.inputIcon} />
                <input
                  type="number"
                  placeholder="TTL (optional)"
                  value={ttl}
                  onChange={(e) => setTtl(e.target.value)}
                  disabled={loading}
                  min="1"
                />
                <select
                  value={ttlUnit}
                  onChange={(e) => setTtlUnit(e.target.value)}
                  disabled={loading}
                >
                  {TTL_UNITS.map(unit => (
                    <option key={unit} value={unit}>{unit}</option>
                  ))}
                </select>
              </div>

              <textarea
                className={styles.valueInput}
                placeholder="Enter value"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                disabled={loading}
                rows={6}
              />
            </>
          )}

          <div className={styles.actions}>
            {activeTab === 'get' && (
              <button
                onClick={handleGet}
                className={styles.actionBtn}
                disabled={loading || !key}
              >
                <FiSearch /> GET
              </button>
            )}
            {activeTab === 'post' && (
              <button
                onClick={() => handleSet('post')}
                className={styles.actionBtn}
                disabled={!canWrite || loading || !key || !value}
              >
                <FiPlus /> CREATE
              </button>
            )}
            {activeTab === 'put' && (
              <button
                onClick={() => handleSet('put')}
                className={styles.actionBtn}
                disabled={!canWrite || loading || !key || !value}
              >
                <FiEdit /> UPDATE
              </button>
            )}
            {activeTab === 'delete' && (
              <button
                onClick={handleDelete}
                className={`${styles.actionBtn} ${styles.deleteBtn}`}
                disabled={!canWrite || loading || !key}
              >
                <FiTrash2 /> DELETE
              </button>
            )}
          </div>
        </div>

        <div className={styles.outputSection}>
          <h3>Result</h3>
          {loading ? (
            <div className={styles.loader}>Loading...</div>
          ) : result ? (
            <div className={styles.result}>
              {result.success ? (
                <>
                  <div className={styles.successBadge}>Success</div>
                  <div className={styles.resultData}>
                    {typeof result.data === 'string' && dataType === 'json' ? (
                      <ReactJson
                        src={formatValue(result.data)}
                        theme="monokai"
                        collapsed={false}
                        displayDataTypes={false}
                      />
                    ) : (
                      <pre>{JSON.stringify(result.data, null, 2)}</pre>
                    )}
                  </div>
                </>
              ) : (
                <>
                  <div className={styles.errorBadge}>Error</div>
                  <div className={styles.errorMessage}>{result.error}</div>
                </>
              )}
            </div>
          ) : (
            <div className={styles.placeholder}>
              Execute an operation to see result
            </div>
          )}

          {history.length > 0 && (
            <div className={styles.history}>
              <h4>Recent Operations</h4>
              <div className={styles.historyList}>
                {history.map((item, index) => (
                  <div key={index} className={styles.historyItem}>
                    <span className={`${styles.historyOp} ${styles[item.operation.toLowerCase()]}`}>
                      {item.operation}
                    </span>
                    <span className={styles.historyKey}>{item.key}</span>
                    <span className={styles.historyTime}>
                      {new Date(item.timestamp).toLocaleTimeString()}
                    </span>
                    <button
                      className={styles.historyCopy}
                      onClick={() => setKey(item.key)}
                    >
                      <FiCopy />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default CacheOperations;