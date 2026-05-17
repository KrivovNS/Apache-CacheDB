const API_BASE_URL = 'http://localhost:8080';

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

  async request(method, endpoint, params = {}, body = null) {
    const url = new URL(`${API_BASE_URL}${endpoint}`);

    Object.keys(params).forEach(key => {
      if (params[key] !== undefined && params[key] !== null) {
        url.searchParams.append(key, params[key]);
      }
    });

    if (this.sessionToken) {
      console.log(`Adding session token to ${endpoint} request:`, this.sessionToken);
      url.searchParams.append('session_token', this.sessionToken);
    } else {
      console.warn(`No session token for ${endpoint} request`);
    }

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
      const response = await fetch(url, options);
      const text = await response.text();
      console.log(`Response status: ${response.status}, body:`, text);

      if (!response.ok) {
        if (response.status === 401) {
          console.log('Session expired, clearing token');
          this.clearSession();
          window.dispatchEvent(new CustomEvent('showLogin'));
        }
        throw new Error(text || `HTTP error! status: ${response.status}`);
      }

      return { data: text, status: response.status };
    } catch (error) {
      console.error('API Request failed:', error);
      throw error;
    }
  }

  // ==================== Аутентификация ====================

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

  // ==================== Базовые операции с кэшем ====================

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

  // ==================== LIST Operations ====================

  async lpush(key, value) {
    console.log(`lpush: key=${key}, value=${value}`);
    return this.request('POST', '/cache', { cmd: 'lpush', key, value });
  }

  async rpush(key, value) {
    console.log(`rpush: key=${key}, value=${value}`);
    return this.request('POST', '/cache', { cmd: 'rpush', key, value });
  }

  async lpop(key) {
    console.log(`lpop: key=${key}`);
    return this.request('GET', '/cache', { cmd: 'lpop', key });
  }

  async rpop(key) {
    console.log(`rpop: key=${key}`);
    return this.request('GET', '/cache', { cmd: 'rpop', key });
  }

  async lrange(key, start, stop) {
    console.log(`lrange: key=${key}, start=${start}, stop=${stop}`);
    return this.request('GET', '/cache', { cmd: 'lrange', key, start, stop });
  }

  async llen(key) {
    console.log(`llen: key=${key}`);
    return this.request('GET', '/cache', { cmd: 'llen', key });
  }

  // ==================== HASH Operations ====================

  async hset(key, field, value) {
    console.log(`hset: key=${key}, field=${field}, value=${value}`);
    return this.request('POST', '/cache', { cmd: 'hset', key, field, value });
  }

  async hget(key, field) {
    console.log(`hget: key=${key}, field=${field}`);
    return this.request('GET', '/cache', { cmd: 'hget', key, field });
  }

  async hdel(key, field) {
    console.log(`hdel: key=${key}, field=${field}`);
    return this.request('DELETE', '/cache', { cmd: 'hdel', key, field });
  }

  async hgetall(key) {
    console.log(`hgetall: key=${key}`);
    return this.request('GET', '/cache', { cmd: 'hgetall', key });
  }

  async hincrby(key, field, increment) {
    console.log(`hincrby: key=${key}, field=${field}, increment=${increment}`);
    return this.request('POST', '/cache', { cmd: 'hincrby', key, field, increment });
  }

  // ==================== Управление пользователями ====================

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

  // ==================== Конфигурация ====================

  async updateConfig(policy, maxMemory, persistence) {
    return this.request('PUT', '/configuration', {
      max_memory_policy: policy,
      max_storage_memory: maxMemory,
      persistence: persistence
    });
  }
}

const api = new ApiService();
export default api;