import React, { useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import api from '../../services/api';
import ReactJson from 'react-json-view';
import { FiPlay, FiTrash2, FiCopy } from 'react-icons/fi';
import styles from './QueryConsole.module.css';

const QueryConsole = () => {
  const { isAuthenticated, user } = useAuth();
  const { showSuccess, showError } = useNotification();

  const [query, setQuery] = useState('GET mykey');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);

  const sampleQueries = [
    { name: 'Get value', query: 'GET mykey' },
    { name: 'Set string', query: 'SET mykey "Hello World" string' },
    { name: 'Set JSON', query: 'SET userdata \'{"name":"John","age":30}\' json' },
    { name: 'Delete key', query: 'DELETE mykey' },
    // Hash commands
    { name: 'HSET', query: 'HSET user:1000 name "Alice"' },
    { name: 'HGET', query: 'HGET user:1000 name' },
    { name: 'HGETALL', query: 'HGETALL user:1000' },
    { name: 'HDEL', query: 'HDEL user:1000 age' },
    // List commands
    { name: 'LPUSH', query: 'LPUSH tasks "task1"' },
    { name: 'RPUSH', query: 'RPUSH tasks "task2"' },
    { name: 'LPOP', query: 'LPOP tasks' },
    { name: 'RPOP', query: 'RPOP tasks' },
    { name: 'LRANGE', query: 'LRANGE tasks 0 -1' },
  ];

  const parseCommand = (queryStr) => {
    const trimmed = queryStr.trim();
    const parts = trimmed.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g);
    if (!parts || parts.length === 0) {
      throw new Error('Empty command');
    }

    const command = parts[0].toUpperCase();
    const args = parts.slice(1).map(arg => {
      if ((arg.startsWith('"') && arg.endsWith('"')) ||
          (arg.startsWith("'") && arg.endsWith("'"))) {
        return arg.slice(1, -1);
      }
      return arg;
    });

    return { command, args };
  };

  const executeQuery = async () => {
    if (!query.trim()) {
      showError('Please enter a query');
      return;
    }

    if (!api.isAuthenticated()) {
      showError('Please login first');
      return;
    }

    setLoading(true);
    const startTime = Date.now();

    try {
      const { command, args } = parseCommand(query);
      let result;
      let resultData;

      switch (command) {
        case 'GET':
          if (args.length < 1) throw new Error('GET requires a key');
          result = await api.getCache(args[0]);
          resultData = result.data;
          break;

        case 'SET':
          if (args.length < 2) throw new Error('SET requires key and value');
          const key = args[0];
          const value = args[1];
          const type = args.length >= 3 ? args[2] : 'string';
          const ttl = args.length >= 4 ? args[3] : null;

          if (!['string', 'json', 'byte[]'].includes(type)) {
            throw new Error('Invalid type. Use: string, json, byte[]');
          }

          result = await api.setCache('post', key, value, type, ttl);
          resultData = result.data;
          break;

        case 'DELETE':
          if (args.length < 1) throw new Error('DELETE requires a key');
          result = await api.deleteCache(args[0]);
          resultData = result.data;
          break;

        case 'SQL':
          if (args.length < 1) throw new Error('SQL requires a query');
          const sqlQuery = args.join(' ');
          result = await api.executeSql(sqlQuery);
          resultData = result.data;
          break;

        // ============ HASH COMMANDS ============
        case 'HSET':
          if (args.length < 3) throw new Error('HSET requires key, field and value');
          result = await api.hset(args[0], args[1], args[2]);
          resultData = result.data;
          break;

        case 'HGET':
          if (args.length < 2) throw new Error('HGET requires key and field');
          result = await api.hget(args[0], args[1]);
          resultData = result.data;
          break;

        case 'HDEL':
          if (args.length < 2) throw new Error('HDEL requires key and field');
          result = await api.hdel(args[0], args[1]);
          resultData = result.data;
          break;

        case 'HGETALL':
          if (args.length < 1) throw new Error('HGETALL requires a key');
          result = await api.hgetall(args[0]);
          resultData = result.data;
          break;

        case 'HKEYS':
          if (args.length < 1) throw new Error('HKEYS requires a key');
          result = await api.hkeys(args[0]);
          resultData = result.data;
          break;

        case 'HLEN':
          if (args.length < 1) throw new Error('HLEN requires a key');
          result = await api.hlen(args[0]);
          resultData = result.data;
          break;

        // ============ LIST COMMANDS ============
        case 'LPUSH':
          if (args.length < 2) throw new Error('LPUSH requires key and value');
          result = await api.lpush(args[0], args[1]);
          resultData = result.data;
          break;

        case 'RPUSH':
          if (args.length < 2) throw new Error('RPUSH requires key and value');
          result = await api.rpush(args[0], args[1]);
          resultData = result.data;
          break;

        case 'LPOP':
          if (args.length < 1) throw new Error('LPOP requires a key');
          result = await api.lpop(args[0]);
          resultData = result.data;
          break;

        case 'RPOP':
          if (args.length < 1) throw new Error('RPOP requires a key');
          result = await api.rpop(args[0]);
          resultData = result.data;
          break;

        case 'LRANGE':
          if (args.length < 3) throw new Error('LRANGE requires key, start and end');
          const start = parseInt(args[1]);
          const end = parseInt(args[2]);
          if (isNaN(start) || isNaN(end)) throw new Error('Start and end must be numbers');
          result = await api.lrange(args[0], start, end);
          resultData = result.data;
          break;

        case 'LLEN':
          if (args.length < 1) throw new Error('LLEN requires a key');
          result = await api.llen(args[0]);
          resultData = result.data;
          break;

        case 'LINDEX':
          if (args.length < 2) throw new Error('LINDEX requires key and index');
          const idx = parseInt(args[1]);
          if (isNaN(idx)) throw new Error('Index must be a number');
          result = await api.lindex(args[0], idx);
          resultData = result.data;
          break;

        default:
          throw new Error(`Unknown command: ${command}. Available: GET, SET, DELETE, SQL, HSET, HGET, HDEL, HGETALL, HKEYS, HLEN, LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, LINDEX`);
      }

      const executionTime = Date.now() - startTime;

      const resultItem = {
        id: Date.now(),
        query,
        result: resultData,
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
        error: error.message || 'Unknown error',
        timestamp: new Date().toISOString(),
        executionTime,
        success: false
      };

      setResults(prev => [resultItem, ...prev]);
      showError(error.message || 'Failed to execute query');
    } finally {
      setLoading(false);
    }
  };

  const addToHistory = (queryStr) => {
    setHistory(prev => {
      const newHistory = [queryStr, ...prev.filter(q => q !== queryStr)];
      return newHistory.slice(0, 20);
    });
  };

  const clearResults = () => {
    setResults([]);
  };

  const clearHistory = () => {
    setHistory([]);
  };

  const copyToClipboard = (text) => {
    const stringText = typeof text === 'object' ? JSON.stringify(text, null, 2) : String(text);
    navigator.clipboard.writeText(stringText);
    showSuccess('Copied to clipboard');
  };

  const formatResultDisplay = (result) => {
    if (typeof result === 'string') {
      try {
        const parsed = JSON.parse(result);
        return (
          <ReactJson
            src={parsed}
            theme="monokai"
            collapsed={false}
            displayDataTypes={false}
            enableClipboard={false}
          />
        );
      } catch {
        return <pre>{result}</pre>;
      }
    }
    if (typeof result === 'object') {
      return (
        <ReactJson
          src={result}
          theme="monokai"
          collapsed={false}
          displayDataTypes={false}
          enableClipboard={false}
        />
      );
    }
    return <pre>{String(result)}</pre>;
  };

  if (!isAuthenticated) {
    return (
      <div className={styles.warning}>
        <h2>Please login to use Query Console</h2>
        <p>You need to be authenticated to execute queries</p>
      </div>
    );
  }

  return (
    <div className={styles.queryConsole}>
      <div className={styles.header}>
        <h1>Query Console</h1>
        <div className={styles.headerActions}>
          <button className={styles.clearBtn} onClick={clearHistory}>
            <FiTrash2 /> Clear History
          </button>
          <button className={styles.clearBtn} onClick={clearResults}>
            <FiTrash2 /> Clear Results
          </button>
        </div>
      </div>

      <div className={styles.userInfo}>
        Logged in as: <strong>{user?.username}</strong> ({user?.permission})
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
                  <FiPlay /> {loading ? 'Executing...' : 'Execute'}
                </button>
              </div>
            </div>

            <textarea
              className={styles.editor}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Enter your query here..."
              rows={6}
              disabled={loading}
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
                {history.map((queryStr, index) => (
                  <div key={index} className={styles.historyItem}>
                    <code>{queryStr}</code>
                    <button
                      className={styles.historyBtn}
                      onClick={() => setQuery(queryStr)}
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
                      formatResultDisplay(result.result)
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