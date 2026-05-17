const API_BASE_URL = process.env.REACT_APP_API_URL || '/api';

class ApiService {
  constructor() {
    this.sessionToken = localStorage.getItem('sessionToken');
    console.log('ApiService initialized with token:', this.sessionToken);
  }

  async get(endpoint, params = {}) {
    return this.request('GET', endpoint, params);
  }

  setSessionToken(token) {
    console.log('Setting session token:', token);
    this.sessionToken = token;
    localStorage.setItem('sessionToken', token);
  }

  clearSession() {
    console.log('Clearing session');
    this.sessionToken = null;
    localStorage.removeItem('sessionToken');
  }

  getSessionToken() {
    return this.sessionToken;
  }

  createUrl(endpoint, params = {}) {
    const normalizedEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
    const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
    const url = new URL(`${API_BASE_URL}${normalizedEndpoint}`, origin);

    Object.keys(params).forEach((key) => {
      if (params[key] !== undefined && params[key] !== null) {
        url.searchParams.append(key, params[key]);
      }
    });

    if (this.sessionToken && normalizedEndpoint !== '/auth') {
      console.log(`Adding session token to ${endpoint} request:`, this.sessionToken);
      url.searchParams.append('session_token', this.sessionToken);
    } else if (!this.sessionToken && normalizedEndpoint !== '/auth') {
      console.warn(`No session token for ${endpoint} request`);
    }

    return url;
  }

  async request(method, endpoint, params = {}, body = null) {
    const url = this.createUrl(endpoint, params);
    const options = {
      method,
      headers: {
        'Content-Type': 'text/plain',
      },
    };

    if (body !== null) {
      options.body = body;
    }

    try {
      console.log(`Making ${method} request to ${url.toString()}`);
      const response = await fetch(url.toString(), options);
      const text = await response.text();
      console.log(`Response status: ${response.status}, body:`, text);

      if (!response.ok) {
        if (response.status === 401) {
          console.log('Session expired, clearing token');
          this.clearSession();
        }
        throw new Error(text || `HTTP error! status: ${response.status}`);
      }

      return { data: text, status: response.status };
    } catch (error) {
      console.error('API Request failed:', error);
      throw error;
    }
  }

  async login(login, password) {
    console.log('Login attempt for:', login);
    const response = await this.request('GET', '/auth', { login, password });

    const tokenMatch = response.data.match(/Session token: ([a-f0-9-]+)/i);
    if (tokenMatch) {
      const token = tokenMatch[1];
      console.log('Extracted token:', token);
      this.setSessionToken(token);
    } else {
      console.warn('No token found in response:', response.data);
    }

    return response.data;
  }

  async getCache(key) {
    return this.request('GET', '/cache', { key });
  }

  async setCache(method, key, value, type = 'string', ttl = null) {
    const params = { key, type };
    if (ttl) params.ttl = ttl;
    console.log(`setCache: ${method} request for key ${key}`, { value, type, ttl });
    return this.request(method.toUpperCase(), '/cache', params, value);
  }

  async deleteCache(key) {
    console.log(`deleteCache: DELETE request for key ${key}`);
    return this.request('DELETE', '/cache', { key });
  }

  async getUsers() {
    return this.request('GET', '/user');
  }

  async createUser(login, password, permission) {
    return this.request('POST', '/user', { login, password, permission });
  }

  async updateUser(login, updates) {
    const params = { login };
    if (updates.new_login) params.new_login = updates.new_login;
    if (updates.password) params.password = updates.password;
    if (updates.permission) params.permission = updates.permission;
    return this.request('PUT', '/user', params);
  }

  async deleteUser(login) {
    return this.request('DELETE', '/user', { login });
  }

  async updateConfig(policy, maxMemory, persistence) {
    return this.request('PUT', '/configuration', {
      max_memory_policy: policy,
      max_storage_memory: maxMemory,
      persistence
    });
  }
}

const api = new ApiService();
export default api;
