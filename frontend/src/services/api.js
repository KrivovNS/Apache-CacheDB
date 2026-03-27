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

    // Добавляем параметры запроса
    Object.keys(params).forEach(key => {
      if (params[key] !== undefined && params[key] !== null) {
        url.searchParams.append(key, params[key]);
      }
    });

    // ВАЖНО: Добавляем session_token к каждому запросу
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
        // Если получили 401 Unauthorized, возможно токен истек
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

  // Аутентификация
  async login(login, password) {
    console.log('Login attempt for:', login);
    const response = await this.request('GET', '/auth', { login, password });

    // Парсим токен из ответа
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

  // Операции с кэшем
  async getCache(key) {
    return this.request('GET', '/cache', { key });
  }

  async setCache(method, key, value, type = 'string', ttl = null) {
    const params = { key, type };
    if (ttl) params.ttl = ttl;

    // Для PUT метода убедимся, что тело запроса передается правильно
    console.log(`setCache: ${method} request for key ${key}`, { value, type, ttl });

    // Важно: для PUT и POST тело запроса должно быть отправлено
    return this.request(method.toUpperCase(), '/cache', params, value);
  }

  async deleteCache(key) {
    console.log(`deleteCache: DELETE request for key ${key}`);
    return this.request('DELETE', '/cache', { key });
  }

  // Управление пользователями
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

  // Конфигурация
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