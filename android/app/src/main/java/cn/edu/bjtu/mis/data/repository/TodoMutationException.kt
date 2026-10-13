package cn.edu.bjtu.mis.data.repository

class TodoMutationException(val code: String, message: String) : IllegalArgumentException(message)
