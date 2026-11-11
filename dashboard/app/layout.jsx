import { Inter } from 'next/font/google'
import './globals.css'
import LiveRefresh from './LiveRefresh'

// Self-hosted by Next at build time; no request to Google from the browser.
const inter = Inter({ subsets: ['latin'], variable: '--font' })

export const metadata = { title: 'Job Alerts' }

export default function RootLayout({ children }) {
  return (
    // Extensions like Dark Reader add attributes to <html> before hydration.
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <body><LiveRefresh />{children}</body>
    </html>
  )
}
