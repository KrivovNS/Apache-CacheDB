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

  const [query, setQuery] = useState('-- Enter your query here\nGET key_name');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);

  const sampleQueries = [
    { name: 'Get value', query: 'GET mykey' },
    { name: 'Set value', query: 'SET mykey "Hello World"' },
    { name: 'Delete key', query: 'DELETE mykey' },
    { name: 'List all keys', query: 'KEYS *' },
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

  const executeParsedQuery = async (query) => {
    const parts = query.trim().split(/\s+/);
    const command = parts[0].toUpperCase();

    switch (command) {
      case 'GET':
        if (parts.length < 2) throw new Error('GET requires a key');
        const response = await api.getCache(parts[1]);
        return response.data;

      case 'SET':
        if (parts.length < 3) throw new Error('SET requires key and value');
        const key = parts[1];
        const value = parts.slice(2).join(' ').replace(/^["']|["']$/g, '');
        await api.setCache('post', key, value);
        return 'OK';

      case 'DELETE':
        if (parts.length < 2) throw new Error('DELETE requires a key');
        await api.deleteCache(parts[1]);
        return 'OK';

      case 'KEYS':
        if (parts.length > 1 && parts[1] !== '*') {
          throw new Error('Only KEYS * is supported');
        }
        return ['key1', 'key2', 'key3']; // Mock response

      default:
        throw new Error(`Unknown command: ${command}`);
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
                      onClick={() => copyToClipboard(JSON.stringify(result.result || result.error))}
                    >
                      <FiCopy />
                    </button>
                  </div>

                  <div className={styles.queryDisplay}>
                    <code>{result.query}</code>
                  </div>

                  <div className={styles.resultDisplay}>
                    {result.success ? (
                      typeof result.result === 'string' && result.result.startsWith('{') ? (
                        <ReactJson
                          src={JSON.parse(result.result)}
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