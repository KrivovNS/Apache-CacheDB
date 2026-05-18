/* eslint-disable import/first */
/* eslint-disable import/order */

// Полифилл для process
if (typeof window !== 'undefined' && !window.process) {
  window.process = { env: { NODE_ENV: 'development' } };
}

import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);