import React, { useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import api from '../../services/api';
import ReactJson from 'react-json-view';
import { FiPlay, FiTrash2, FiCopy } from 'react-icons/fi';
import styles from './QueryConsole.module.css';

const QueryConsole = () => {
  const { isAuthenticated } = useAuth();
  const { showSuccess, showError } = useNotification();

  const [query, setQuery] = useState('-- LIST commands example:\nLPUSH mylist hello\nLRANGE mylist 0 -1');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);

  const sampleQueries = [
    { name: 'GET value', query: 'GET mykey' },
    { name: 'SET string', query: 'SET mykey "Hello World" string' },
    { name: 'LPUSH list', query: 'LPUSH mylist value1' },
    { name: 'RPUSH list', query: 'RPUSH mylist value2' },
    { name: 'LRANGE list', query: 'LRANGE mylist 0 -1' },
    { name: 'LLEN list', query: 'LLEN mylist' },
    { name: 'LPOP list', query: 'LPOP mylist' },
    { name: 'HSET hash', query: 'HSET myhash field1 value1' },
    { name: 'HGET hash', query: 'HGET myhash field1' },
    { name: 'HGETALL hash', query: 'HGETALL myhash' },
    { name: 'HINCRBY hash', query: 'HINCRBY myhash counter 5' },
    { name: 'DELETE key', query: 'DELETE mykey' },
  ];

  const executeQuery = async () => {
    if (!query.trim()) {
      showError('Please enter a query');
      return;
    }

    setLoading(true);
    const startTime = Date.now();

    try {
      const result = await executeParsedQuery(query);
      const executionTime = Date.now() - startTime;

      const resultItem = {
        id: Date.now(),
        query,
        result,
        timestamp: new Date().toISOString(),
        executionTime,
        success: true
      };

      setResults(prev => [resultItem, ...prev]);
      addToHistory(query);
      showSuccess(`Query executed in ${executionTime}ms`);
    } catch (error) {
      const executionTime = Date.now() - startTime;

      const resultItem = {
        id: Date.now(),
        query,
        error: error.message,
        timestamp: new Date().toISOString(),
        executionTime,
        success: false
      };

      setResults(prev => [resultItem, ...prev]);
      showError(error.message);
    } finally {
      setLoading(false);
    }
  };

  const executeParsedQuery = async (queryStr) => {
    const parts = queryStr.trim().split(/\s+/);
    const command = parts[0].toUpperCase();

    switch (command) {
      // --- STRING/JSON/BYTES operations ---
      case 'GET':
        if (parts.length < 2) throw new Error('GET requires a key');
        const getResp = await api.getCache(parts[1]);
        const data = getResp.data;
        try {
          return JSON.parse(data);
        } catch {
          return data;
        }

      case 'SET':
        if (parts.length < 3) throw new Error('SET requires key and value');
        const key = parts[1];
        let value = parts.slice(2).join(' ').replace(/^["']|["']$/g, '');
        let type = parts.length > 3 ? parts[3] : 'string';
        if (!['string', 'json', 'byte[]'].includes(type)) type = 'string';
        await api.setCache('post', key, value, type);
        return 'OK';

      case 'DELETE':
        if (parts.length < 2) throw new Error('DELETE requires a key');
        await api.deleteCache(parts[1]);
        return 'OK';

      // --- LIST operations ---
      case 'LPUSH':
        if (parts.length < 3) throw new Error('LPUSH requires key and value');
        await api.lpush(parts[1], parts[2]);
        return 'OK';

      case 'RPUSH':
        if (parts.length < 3) throw new Error('RPUSH requires key and value');
        await api.rpush(parts[1], parts[2]);
        return 'OK';

      case 'LPOP':
        if (parts.length < 2) throw new Error('LPOP requires a key');
        const lpopResp = await api.lpop(parts[1]);
        return lpopResp.data;

      case 'RPOP':
        if (parts.length < 2) throw new Error('RPOP requires a key');
        const rpopResp = await api.rpop(parts[1]);
        return rpopResp.data;

      case 'LRANGE':
        if (parts.length < 4) throw new Error('LRANGE requires key, start, stop');
        const start = parseInt(parts[2], 10);
        const stop = parseInt(parts[3], 10);
        if (isNaN(start) || isNaN(stop)) throw new Error('start and stop must be integers');
        const lrangeResp = await api.lrange(parts[1], start, stop);
        return lrangeResp.data;

      case 'LLEN':
        if (parts.length < 2) throw new Error('LLEN requires a key');
        const llenResp = await api.llen(parts[1]);
        return parseInt(llenResp.data, 10);

      // --- HASH operations ---
      case 'HSET':
        if (parts.length < 4) throw new Error('HSET requires key, field, value');
        await api.hset(parts[1], parts[2], parts[3]);
        return 'OK';

      case 'HGET':
        if (parts.length < 3) throw new Error('HGET requires key and field');
        const hgetResp = await api.hget(parts[1], parts[2]);
        return hgetResp.data;

      case 'HDEL':
        if (parts.length < 3) throw new Error('HDEL requires key and field');
        await api.hdel(parts[1], parts[2]);
        return 'OK';

      case 'HGETALL':
        if (parts.length < 2) throw new Error('HGETALL requires a key');
        const hgetallResp = await api.hgetall(parts[1]);
        return hgetallResp.data;

      case 'HINCRBY':
        if (parts.length < 4) throw new Error('HINCRBY requires key, field, increment');
        const incr = parseInt(parts[3], 10);
        if (isNaN(incr)) throw new Error('increment must be a number');
        const hincrResp = await api.hincrby(parts[1], parts[2], incr);
        return parseInt(hincrResp.data, 10);

      default:
        throw new Error(`Unknown command: ${command}. Supported: GET, SET, DELETE, LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, HSET, HGET, HDEL, HGETALL, HINCRBY`);
    }
  };

  const addToHistory = (query) => {
    setHistory(prev => {
      const newHistory = [query, ...prev.filter(q => q !== query)];
      return newHistory.slice(0, 20);
    });
  };

  const clearResults = () => {
    setResults([]);
  };

  const copyToClipboard = (text) => {
    if (typeof text === 'object') text = JSON.stringify(text, null, 2);
    navigator.clipboard.writeText(text);
    showSuccess('Copied to clipboard');
  };

  if (!isAuthenticated) {
    return (
      <div className={styles.warning}>
        <h2>Please login to use Query Console</h2>
      </div>
    );
  }

  return (
    <div className={styles.queryConsole}>
      <div className={styles.header}>
        <h1>Query Console</h1>
        <div className={styles.headerActions}>
          <button className={styles.clearBtn} onClick={clearResults}>
            <FiTrash2 /> Clear Results
          </button>
        </div>
      </div>

      <div className={styles.content}>
        <div className={styles.querySection}>
          <div className={styles.queryEditor}>
            <div className={styles.editorHeader}>
              <h3>Query Editor</h3>
              <div className={styles.editorActions}>
                <button
                  className={styles.executeBtn}
                  onClick={executeQuery}
                  disabled={loading}
                >
                  <FiPlay /> Execute
                </button>
              </div>
            </div>

            <textarea
              className={styles.editor}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              rows={8}
            />
          </div>

          <div className={styles.samples}>
            <h3>Sample Queries</h3>
            <div className={styles.sampleList}>
              {sampleQueries.map((sample, index) => (
                <button
                  key={index}
                  className={styles.sampleItem}
                  onClick={() => setQuery(sample.query)}
                >
                  <span className={styles.sampleName}>{sample.name}</span>
                  <code className={styles.sampleQuery}>{sample.query}</code>
                </button>
              ))}
            </div>
          </div>

          {history.length > 0 && (
            <div className={styles.history}>
              <h3>Recent Queries</h3>
              <div className={styles.historyList}>
                {history.map((query, index) => (
                  <div key={index} className={styles.historyItem}>
                    <code>{query}</code>
                    <button
                      className={styles.historyBtn}
                      onClick={() => setQuery(query)}
                      title="Use query"
                    >
                      <FiPlay />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className={styles.resultsSection}>
          <h3>Results</h3>

          {results.length === 0 ? (
            <div className={styles.placeholder}>
              Execute a query to see results
            </div>
          ) : (
            <div className={styles.resultsList}>
              {results.map(result => (
                <div key={result.id} className={styles.resultItem}>
                  <div className={styles.resultHeader}>
                    <span className={styles.resultTime}>
                      {new Date(result.timestamp).toLocaleTimeString()}
                    </span>
                    <span className={styles.executionTime}>
                      {result.executionTime}ms
                    </span>
                    <span className={`${styles.resultStatus} ${result.success ? styles.success : styles.error}`}>
                      {result.success ? 'Success' : 'Error'}
                    </span>
                    <button
                      className={styles.copyBtn}
                      onClick={() => copyToClipboard(result.success ? result.result : result.error)}
                    >
                      <FiCopy />
                    </button>
                  </div>

                  <div className={styles.queryDisplay}>
                    <code>{result.query}</code>
                  </div>

                  <div className={styles.resultDisplay}>
                    {result.success ? (
                      typeof result.result === 'object' ? (
                        <ReactJson
                          src={result.result}
                          theme="monokai"
                          collapsed={false}
                          displayDataTypes={false}
                        />
                      ) : (
                        <pre>{JSON.stringify(result.result, null, 2)}</pre>
                      )
                    ) : (
                      <div className={styles.errorMessage}>
                        {result.error}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default QueryConsole;