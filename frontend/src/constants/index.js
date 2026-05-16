export const DATA_TYPES = ['string', 'json', 'byte[]'];

export const TTL_UNITS = ['ms', 's', 'm', 'h', 'd'];

export const MEMORY_POLICIES = [
  'noeviction',
  'allkeys-lru',
  'volatile-lru',
  'allkeys-lfu',
  'volatile-lfu'
];

export const PERMISSIONS = ['reader', 'admin', 'superadmin'];

export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE'];

export const ROUTES = {
  CACHE: '/cache',
  USERS: '/users',
  CONSOLE: '/console'
};

export const DEFAULT_USER = {
  login: 'default',
  password: 'admin123'
};

export const APP_CONFIG = {
  name: 'CacheDB',
  version: '1.0.0',
  apiUrl: process.env.REACT_APP_API_URL || 'http://localhost:8080'
};
