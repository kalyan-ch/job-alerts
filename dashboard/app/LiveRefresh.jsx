'use client'
import { useRouter } from 'next/navigation'
import { useEffect } from 'react'

// Re-renders the server page in place when a run changes. EventSource reconnects on its own.
export default function LiveRefresh() {
  const router = useRouter()
  useEffect(() => {
    const es = new EventSource('/api/events')
    es.onmessage = () => router.refresh()
    return () => es.close()
  }, [router])
  return null
}
