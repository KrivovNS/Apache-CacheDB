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
    if (token) {
      localStorage.setItem('sessionToken', token);
    } else {
      localStorage.removeItem('sessionToken');
    }
  }

  clearSession() {
    console.log('Clearing session');
    this.sessionToken = null;
    localStorage.removeItem('sessionToken');
    localStorage.removeItem('cacheDbUser');
  }

  getSessionToken() {
    return this.sessionToken;
  }

  isAuthenticated() {
    return !!this.sessionToken;
  }

  createUrl(endpoint, params = {}) {
    const normalizedEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
    const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
    const url = new URL(`${API_BASE_URL}${normalizedEndpoint}`, origin);

    Object.keys(params).forEach((key) => {
      if (params[key] !== undefined && params[key] !== null && params[key] !== '') {
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

    if (body !== null && body !== undefined) {
      options.body = typeof body === 'string' ? body : String(body);
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
          if (typeof window !== 'undefined') {
            window.dispatchEvent(new CustomEvent('showLogin'));
          }
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
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { key });
  }

  async setCache(method, key, value, type = 'string', ttl = null) {
    if (!key) {
      throw new Error('Key is required');
    }
    if (value === undefined || value === null) {
      throw new Error('Value is required');
    }

    const params = { key, type };
    if (ttl && ttl.trim()) {
      params.ttl = ttl;
    }
    console.log(`setCache: ${method} request for key ${key}`, { value, type, ttl });
    return this.request(method.toUpperCase(), '/cache', params, value);
  }

  async deleteCache(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    console.log(`deleteCache: DELETE request for key ${key}`);
    return this.request('DELETE', '/cache', { key });
  }

  async getUsers() {
    return this.request('GET', '/user');
  }

  async createUser(login, password, permission) {
    if (!login || !password || !permission) {
      throw new Error('Login, password and permission are required');
    }
    return this.request('POST', '/user', { login, password, permission });
  }

  async updateUser(login, updates) {
    if (!login) {
      throw new Error('Login is required');
    }
    const params = { login };
    if (updates.new_login) params.new_login = updates.new_login;
    if (updates.password) params.password = updates.password;
    if (updates.permission) params.permission = updates.permission;
    return this.request('PUT', '/user', params);
  }

  async deleteUser(login) {
    if (!login) {
      throw new Error('Login is required');
    }
    return this.request('DELETE', '/user', { login });
  }

  async updateConfig(policy, maxMemory, persistence) {
    if (!policy || !maxMemory || persistence === undefined) {
      throw new Error('Policy, maxMemory and persistence are required');
    }
    return this.request('PUT', '/configuration', {
      max_memory_policy: policy,
      max_storage_memory: maxMemory,
      persistence: String(persistence)
    });
  }

  async executeSql(sql) {
    if (!sql) {
      throw new Error('SQL query is required');
    }
    return this.request('GET', '/cache', { sql });
  }

  // ============ HASH OPERATIONS ============

  async hset(key, field, value) {
    if (!key || !field) {
      throw new Error('Key and field are required');
    }
    if (value === undefined || value === null) {
      throw new Error('Value is required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hset',
      key, 
      field, 
      value 
    });
  }

  async hget(key, field) {
    if (!key || !field) {
      throw new Error('Key and field are required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hget',
      key, 
      field 
    });
  }

  async hdel(key, field) {
    if (!key || !field) {
      throw new Error('Key and field are required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hdel',
      key, 
      field 
    });
  }

  async hgetall(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hgetall',
      key 
    });
  }

  async hkeys(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hkeys',
      key 
    });
  }

  async hlen(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      hash_op: 'hlen',
      key 
    });
  }

  // ============ LIST OPERATIONS ============

  async lpush(key, value) {
    if (!key || !value) {
      throw new Error('Key and value are required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'lpush',
      key, 
      value 
    });
  }

  async rpush(key, value) {
    if (!key || !value) {
      throw new Error('Key and value are required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'rpush',
      key, 
      value 
    });
  }

  async lpop(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'lpop',
      key 
    });
  }

  async rpop(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'rpop',
      key 
    });
  }

  async lrange(key, start, end) {
    if (!key) {
      throw new Error('Key is required');
    }
    if (start === undefined || end === undefined) {
      throw new Error('Start and end indices are required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'lrange',
      key, 
      start: String(start), 
      end: String(end) 
    });
  }

  async llen(key) {
    if (!key) {
      throw new Error('Key is required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'llen',
      key 
    });
  }

  async lindex(key, index) {
    if (!key) {
      throw new Error('Key is required');
    }
    if (index === undefined) {
      throw new Error('Index is required');
    }
    return this.request('GET', '/cache', { 
      list_op: 'lindex',
      key, 
      index: String(index) 
    });
  }
}

const api = new ApiService();
export default api;