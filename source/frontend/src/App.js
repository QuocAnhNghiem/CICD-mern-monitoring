import React, { useState, useEffect } from 'react';
import axios from 'axios';

const API_URL = process.env.REACT_APP_API_URL || 'http://localhost:5000';

function App() {
  const [items, setItems] = useState([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [editId, setEditId] = useState(null);
  const [loading, setLoading] = useState(false);

  // Fetch all items
  const fetchItems = async () => {
    try {
      setLoading(true);
      const res = await axios.get(`${API_URL}/api/items`);
      setItems(res.data);
    } catch (err) {
      console.error('Error fetching items:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchItems();
  }, []);

  // Create or Update item
  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!name.trim()) return;

    try {
      if (editId) {
        await axios.put(`${API_URL}/api/items/${editId}`, { name, description });
        setEditId(null);
      } else {
        await axios.post(`${API_URL}/api/items`, { name, description });
      }
      setName('');
      setDescription('');
      fetchItems();
    } catch (err) {
      console.error('Error saving item:', err);
    }
  };

  // Delete item
  const handleDelete = async (id) => {
    if (!window.confirm('Bạn có chắc muốn xóa?')) return;
    try {
      await axios.delete(`${API_URL}/api/items/${id}`);
      fetchItems();
    } catch (err) {
      console.error('Error deleting item:', err);
    }
  };

  // Edit item - fill form
  const handleEdit = (item) => {
    setEditId(item._id);
    setName(item.name);
    setDescription(item.description || '');
  };

  // Cancel edit
  const handleCancel = () => {
    setEditId(null);
    setName('');
    setDescription('');
  };

  return (
    <div className="container">
      <h1>📦 MERN Stack CI/CD Demo</h1>
      <p className="subtitle">Full CRUD Application with Docker Swarm & Jenkins</p>

      {/* Form */}
      <form onSubmit={handleSubmit} className="form">
        <input
          type="text"
          placeholder="Item name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
        />
        <input
          type="text"
          placeholder="Description (optional)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        <div className="form-actions">
          <button type="submit" className={editId ? 'btn-update' : 'btn-add'}>
            {editId ? '✏️ Update' : '➕ Add Item'}
          </button>
          {editId && (
            <button type="button" className="btn-cancel" onClick={handleCancel}>
              ❌ Cancel
            </button>
          )}
        </div>
      </form>

      {/* Items List */}
      {loading ? (
        <p className="loading">Loading...</p>
      ) : items.length === 0 ? (
        <p className="empty">No items yet. Add one above!</p>
      ) : (
        <ul className="item-list">
          {items.map((item) => (
            <li key={item._id} className="item-card">
              <div className="item-info">
                <strong>{item.name}</strong>
                {item.description && <p>{item.description}</p>}
                <small>{new Date(item.createdAt).toLocaleString()}</small>
              </div>
              <div className="item-actions">
                <button className="btn-edit" onClick={() => handleEdit(item)}>✏️</button>
                <button className="btn-delete" onClick={() => handleDelete(item._id)}>🗑️</button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <footer>
        <p>Items: {items.length} | API: {API_URL}</p>
      </footer>
    </div>
  );
}

export default App;
