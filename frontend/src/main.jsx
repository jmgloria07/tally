import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { migrateLegacyKeys } from './storage.js';
import './style.css';

// Runs before anything reads storage. Safe to delete once no browser still holds fitlocal.* keys.
migrateLegacyKeys({
  'fitlocal.workoutDraft.v1': 'tally.workoutDraft.v1',
  'fitlocal.workoutDraft.corrupt': 'tally.workoutDraft.corrupt',
  'fitlocal.chat.v1': 'tally.chat.v1',
});

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
