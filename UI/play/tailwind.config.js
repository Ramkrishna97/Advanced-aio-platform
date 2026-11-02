/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: { ink: '#143d36', mint: '#1d8a6b', mist: '#eff8f3' },
      boxShadow: { glow: '0 18px 50px rgba(31, 118, 91, .16)' },
    },
  },
  plugins: [],
};
