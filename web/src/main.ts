import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api'
import { useAuth } from './stores/auth'
import './styles/base.css'
import './styles/editor.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

setUnauthorizedHandler(() => {
  const auth = useAuth()
  auth.user = null
  const current = router.currentRoute.value
  if (current.name !== 'login') router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

app.mount('#app')
