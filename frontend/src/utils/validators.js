export const validateKey = (key) => {
  if (!key) return 'Key is required';
  if (key.length < 1) return 'Key must be at least 1 character';
  if (key.length > 255) return 'Key must be less than 255 characters';
  if (!/^[a-zA-Z0-9_\-:.]+$/.test(key)) {
    return 'Key can only contain letters, numbers, underscores, hyphens, colons, and dots';
  }
  return null;
};

export const validateLogin = (login) => {
  if (!login) return 'Login is required';
  if (login.length < 3) return 'Login must be at least 3 characters';
  if (login.length > 50) return 'Login must be less than 50 characters';
  if (!/^[a-zA-Z0-9_]+$/.test(login)) {
    return 'Login can only contain letters, numbers, and underscores';
  }
  return null;
};

export const validatePassword = (password) => {
  if (!password) return 'Password is required';
  if (password.length < 6) return 'Password must be at least 6 characters';
  if (password.length > 255) return 'Password must be less than 255 characters';
  return null;
};

export const validateTTL = (ttl) => {
  if (!ttl) return null;

  const regex = /^(\d+)(ms|s|m|h|d)?$/;
  if (!regex.test(ttl)) {
    return 'Invalid TTL format. Use number + optional unit (ms, s, m, h, d)';
  }

  const match = ttl.match(regex);
  const value = parseInt(match[1]);
  if (value <= 0) return 'TTL must be positive';

  return null;
};

export const validateJSON = (str) => {
  try {
    JSON.parse(str);
    return null;
  } catch (e) {
    return 'Invalid JSON format';
  }
};

export const validateBytes = (str) => {
  // Check if it's base64
  try {
    atob(str);
    return null;
  } catch {
    // Check if it's hex
    if (/^[0-9a-fA-F]+$/.test(str) && str.length % 2 === 0) {
      return null;
    }
    return 'Invalid bytes format. Use base64 or hex';
  }
};