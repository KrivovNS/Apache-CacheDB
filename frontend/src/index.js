// Затем идут все остальные импорты
import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';

// Полифилл для process ДОЛЖЕН быть самым первым
if (typeof window !== 'undefined' && !window.process) {
  window.process = { env: { NODE_ENV: 'development' } };
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);